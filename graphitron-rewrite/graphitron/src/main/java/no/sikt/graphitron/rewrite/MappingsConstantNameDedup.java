package no.sikt.graphitron.rewrite;

import graphql.schema.FieldCoordinates;
import no.sikt.graphitron.rewrite.model.ErrorChannel;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.GraphitronType.ErrorType;
import no.sikt.graphitron.rewrite.model.GraphitronType.ErrorType.ExceptionHandler;
import no.sikt.graphitron.rewrite.model.GraphitronType.ErrorType.Handler;
import no.sikt.graphitron.rewrite.model.GraphitronType.ErrorType.SqlStateHandler;
import no.sikt.graphitron.rewrite.model.GraphitronType.ErrorType.ValidationHandler;
import no.sikt.graphitron.rewrite.model.GraphitronType.ErrorType.VendorCodeHandler;
import no.sikt.graphitron.rewrite.model.MutationField;
import no.sikt.graphitron.rewrite.model.QueryField;
import no.sikt.graphitron.rewrite.model.WithErrorChannel;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Classifier-side cross-field pass that resolves the final
 * {@link ErrorChannel#mappingsConstantName} for every classified {@link WithErrorChannel}
 * field, applying the §3 hash-suffix dedup rule:
 *
 * <ul>
 *   <li>Two fetchers returning the same payload class with identical channel declarations
 *       share one constant (no suffix; the bare {@code SCREAMING_SNAKE} payload-class name).</li>
 *   <li>Two fetchers returning the same payload class with different channel declarations get
 *       distinct constants : the first-seen channel keeps the bare name; subsequent distinct
 *       shapes get a suffix derived from the canonicalised handler list
 *       ({@code FILM_PAYLOAD_A1B2C3D4}). The suffix is an 8-hex-char (32-bit) prefix of the
 *       SHA-256 of the canonicalised mapping list, uppercased.</li>
 *   <li>Different payload classes never share a constant even if their handler lists are
 *       byte-identical, since the per-fetcher payload-factory differs and a shared constant
 *       would be misleading.</li>
 * </ul>
 *
 * <p>Spec: {@code error-handling-parity.md} §3 ({@code ErrorMappings} subsection). The per-field
 * classifier runs first and stamps every {@link ErrorChannel} with the bare payload-class name;
 * this pass runs immediately after the per-field loop and before {@link GraphitronSchema}
 * construction, walking the classified fields to compute hashes, apply suffixes where needed,
 * and rewrite each {@link WithErrorChannel} carrier so the resolved name lands on
 * {@code ErrorChannel.mappingsConstantName} before any emitter sees the schema. Conceptually
 * part of the classifier surface; physically a separate utility because the per-field
 * classifier can't observe collisions until every field has been seen.
 */
public final class MappingsConstantNameDedup {

    private MappingsConstantNameDedup() {}

    /**
     * Returns a copy of {@code fields} where every {@link WithErrorChannel} carrier whose
     * channel needs a suffix has been re-constructed with the resolved name on its
     * {@link ErrorChannel}; carriers that don't need a suffix are returned by reference.
     */
    public static Map<FieldCoordinates, GraphitronField> apply(
            Map<FieldCoordinates, GraphitronField> fields) {
        // Group every classified channel by its payload class, preserving classification order.
        Map<String, List<ErrorChannel>> byPayload = new LinkedHashMap<>();
        for (var entry : fields.entrySet()) {
            var ch = channelOf(entry.getValue());
            if (ch == null) continue;
            byPayload
                .computeIfAbsent(ch.payloadClass().reflectionName(), k -> new ArrayList<>())
                .add(ch);
        }

        // For each payload class, compute the resolved name per ErrorChannel.
        Map<ErrorChannel, String> resolved = new IdentityHashMap<>();
        for (var entry : byPayload.entrySet()) {
            var channels = entry.getValue();
            // Group by canonical handler-list hash, preserving first-seen order.
            Map<String, List<ErrorChannel>> byHash = new LinkedHashMap<>();
            for (var ch : channels) {
                byHash.computeIfAbsent(canonicalHash(ch), h -> new ArrayList<>()).add(ch);
            }
            String bare = channels.get(0).mappingsConstantName();
            if (byHash.size() == 1) {
                // Single shape across all channels for this payload : every channel shares the
                // bare name. No suffix needed.
                for (var ch : channels) resolved.put(ch, bare);
            } else {
                // Multiple shapes coexist. First-seen shape keeps the bare name; subsequent
                // shapes get the 8-hex suffix. Suffix collisions across distinct shapes are
                // unreachable (same hash → same suffix → same group), so iteration is safe.
                boolean first = true;
                for (var hashEntry : byHash.entrySet()) {
                    String name = first
                        ? bare
                        : bare + "_" + hashEntry.getKey().substring(0, 8).toUpperCase();
                    first = false;
                    for (var ch : hashEntry.getValue()) resolved.put(ch, name);
                }
            }
        }

        // Rewrite each WithErrorChannel field whose channel got a non-bare resolved name.
        // Fields whose channel keeps the bare name (the common case) pass through by reference.
        Map<FieldCoordinates, GraphitronField> rewritten = new LinkedHashMap<>(fields.size());
        for (var entry : fields.entrySet()) {
            var field = entry.getValue();
            var ch = channelOf(field);
            if (ch == null) {
                rewritten.put(entry.getKey(), field);
                continue;
            }
            String resolvedName = resolved.get(ch);
            if (resolvedName.equals(ch.mappingsConstantName())) {
                rewritten.put(entry.getKey(), field);
                continue;
            }
            var newChannel = new ErrorChannel(
                ch.mappedErrorTypes(), ch.payloadClass(), ch.errorsSlotIndex(),
                ch.defaultedSlots(), resolvedName);
            rewritten.put(entry.getKey(), withResolvedChannel(field, newChannel));
        }
        return rewritten;
    }

    /**
     * Returns the present {@link ErrorChannel} on a {@link WithErrorChannel} field, or
     * {@code null} for fields that don't carry one. Plain {@code Optional.get()} avoidance so
     * the caller can use null as the no-channel sentinel without an unwrap.
     */
    private static ErrorChannel channelOf(GraphitronField field) {
        if (field instanceof WithErrorChannel w) {
            return w.errorChannel().orElse(null);
        }
        return null;
    }

    /**
     * Re-constructs {@code field} with {@code newChannel} swapped in. Pattern-matches each
     * {@link WithErrorChannel} variant; new variants must be added here when introduced.
     */
    private static GraphitronField withResolvedChannel(GraphitronField field, ErrorChannel newChannel) {
        var present = Optional.of(newChannel);
        return switch (field) {
            case MutationField.MutationInsertTableField f -> new MutationField.MutationInsertTableField(
                f.parentTypeName(), f.name(), f.location(), f.returnExpression(), f.tableInputArg(),
                present);
            case MutationField.MutationUpdateTableField f -> new MutationField.MutationUpdateTableField(
                f.parentTypeName(), f.name(), f.location(), f.returnExpression(), f.tableInputArg(),
                present);
            case MutationField.MutationDeleteTableField f -> new MutationField.MutationDeleteTableField(
                f.parentTypeName(), f.name(), f.location(), f.returnExpression(), f.tableInputArg(),
                present);
            case MutationField.MutationUpsertTableField f -> new MutationField.MutationUpsertTableField(
                f.parentTypeName(), f.name(), f.location(), f.returnExpression(), f.tableInputArg(),
                present);
            case MutationField.MutationServiceTableField f -> new MutationField.MutationServiceTableField(
                f.parentTypeName(), f.name(), f.location(), f.returnType(), f.method(), present, f.resultAssembly());
            case MutationField.MutationServiceRecordField f -> new MutationField.MutationServiceRecordField(
                f.parentTypeName(), f.name(), f.location(), f.returnType(), f.method(), present, f.resultAssembly());
            case QueryField.QueryServiceTableField f -> new QueryField.QueryServiceTableField(
                f.parentTypeName(), f.name(), f.location(), f.returnType(), f.method(), present, f.resultAssembly());
            case QueryField.QueryServiceRecordField f -> new QueryField.QueryServiceRecordField(
                f.parentTypeName(), f.name(), f.location(), f.returnType(), f.method(), present, f.resultAssembly());
            default -> throw new IllegalStateException(
                "MappingsConstantNameDedup: unhandled WithErrorChannel variant "
                    + field.getClass().getName()
                    + "; add a case to withResolvedChannel for the new variant");
        };
    }

    /**
     * Canonicalises a channel's flattened handler list and returns the SHA-256 hex digest. The
     * canonical form walks {@code mappedErrorTypes} in source order; for each {@link ErrorType}
     * walks {@code handlers} in source order, writing one fingerprint line per handler with the
     * variant tag, criteria, optional matches, and optional description. Identical handler lists
     * across different channels for the same payload class produce the same hash.
     */
    private static String canonicalHash(ErrorChannel channel) {
        var sb = new StringBuilder();
        for (var et : channel.mappedErrorTypes()) {
            for (var h : et.handlers()) {
                sb.append(handlerLine(et.name(), h)).append('\n');
            }
        }
        try {
            var digest = MessageDigest.getInstance("SHA-256")
                .digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            var hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable on this JRE", e);
        }
    }

    private static String handlerLine(String errorTypeName, Handler h) {
        return switch (h) {
            case ExceptionHandler eh -> "E|" + errorTypeName + "|" + eh.exceptionClassName()
                + "|" + eh.matches().orElse("") + "|" + eh.description().orElse("");
            case SqlStateHandler sh -> "S|" + errorTypeName + "|" + sh.sqlState()
                + "|" + sh.matches().orElse("") + "|" + sh.description().orElse("");
            case VendorCodeHandler vh -> "V|" + errorTypeName + "|" + vh.vendorCode()
                + "|" + vh.matches().orElse("") + "|" + vh.description().orElse("");
            case ValidationHandler vh -> "L|" + errorTypeName + "||"
                + "|" + vh.description().orElse("");
        };
    }
}
