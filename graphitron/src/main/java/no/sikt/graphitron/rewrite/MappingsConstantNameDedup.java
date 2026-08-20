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
import no.sikt.graphitron.rewrite.model.ChildField;
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
        // Group every classified channel under one key per payload-equivalent identity. The
        // sealed split picks the grouping per arm so each consumer dedups within its own surface:
        // PayloadClass arms collapse by payload-class binary name (developer-payload-equivalent);
        // LocalContext arms collapse by their bare mappingsConstantName (the wrapper SDL type
        // name) because no payload class is consulted on that catch path. Classification order
        // is preserved within each key.
        Map<String, List<ErrorChannel>> byGroup = new LinkedHashMap<>();
        for (var entry : fields.entrySet()) {
            var ch = channelOf(entry.getValue());
            if (ch == null) continue;
            byGroup
                .computeIfAbsent(groupKey(ch), k -> new ArrayList<>())
                .add(ch);
        }

        // For each group, compute the resolved name per ErrorChannel.
        Map<ErrorChannel, String> resolved = new IdentityHashMap<>();
        for (var entry : byGroup.entrySet()) {
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
            rewritten.put(entry.getKey(), withResolvedChannel(field, resolvedName));
        }
        return rewritten;
    }

    /**
     * Returns the dedup grouping key for a channel: payload-class binary name for
     * {@link ErrorChannel.PayloadClass}, bare {@code mappingsConstantName} for
     * {@link ErrorChannel.LocalContext}. The two namespaces never collide at the dedup level:
     * a {@code PayloadClass} group key contains dots ({@code com.example.FilmPayload}) while
     * a {@code LocalContext} group key is a SCREAMING_SNAKE identifier
     * ({@code FILM_PAYLOAD}); even so, the sealed switch keeps the contract explicit rather
     * than relying on the namespace separation.
     */
    private static String groupKey(ErrorChannel ch) {
        return switch (ch) {
            case ErrorChannel.Mapped m -> m.mappingsConstantName();
            case ErrorChannel.PayloadClass p -> p.payloadClass().reflectionName();
            case ErrorChannel.LocalContext lc -> lc.mappingsConstantName();
        };
    }

    /**
     * Re-constructs a {@link ErrorChannel.Mapped} channel with {@code newName} swapped onto its
     * {@code mappingsConstantName} slot. Arm-typed (with {@link #renameRouted} as its
     * {@link ErrorChannel.RouterDispatched} sibling) so {@link #withResolvedChannel} can rebuild
     * each field variant with the narrowed channel component its record declares.
     */
    private static ErrorChannel.Mapped renameMapped(ErrorChannel.Mapped m, String newName) {
        return new ErrorChannel.Mapped(m.mappedErrorTypes(), newName);
    }

    /**
     * Re-constructs a {@link ErrorChannel.RouterDispatched} channel with {@code newName} swapped
     * onto its {@code mappingsConstantName} slot, preserving the sealed arm.
     */
    private static ErrorChannel.RouterDispatched renameRouted(
            ErrorChannel.RouterDispatched ch, String newName) {
        return switch (ch) {
            case ErrorChannel.PayloadClass p -> new ErrorChannel.PayloadClass(
                p.mappedErrorTypes(), p.payloadClass(), p.errorsSlot(),
                p.defaultedSlots(), newName);
            case ErrorChannel.LocalContext lc -> new ErrorChannel.LocalContext(
                lc.mappedErrorTypes(), newName);
        };
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
     * Re-constructs {@code field} with its channel renamed to {@code newName}. Pattern-matches
     * each {@link WithErrorChannel} variant so the rebuild goes through the variant's own
     * narrowed channel component; new variants must be added here when introduced. Only called
     * for fields whose channel is present.
     */
    private static GraphitronField withResolvedChannel(GraphitronField field, String newName) {
        return switch (field) {
            case MutationField.DmlTableField f -> new MutationField.DmlTableField(
                f.parentTypeName(), f.name(), f.location(), f.returnExpression(),
                f.write(), f.errorChannel().map(c -> renameRouted(c, newName)));
            case MutationField.MutationServiceTableField f -> new MutationField.MutationServiceTableField(
                f.parentTypeName(), f.name(), f.location(), f.returnType(), f.serviceMethodCall(), f.errorChannel().map(c -> renameMapped(c, newName)));
            case MutationField.MutationServiceRecordField f -> new MutationField.MutationServiceRecordField(
                f.parentTypeName(), f.name(), f.location(), f.returnType(), f.serviceMethodCall(), f.errorChannel().map(c -> renameMapped(c, newName)));
            case QueryField.QueryServiceTableField f -> new QueryField.QueryServiceTableField(
                f.parentTypeName(), f.name(), f.location(), f.returnType(), f.serviceMethodCall(), f.errorChannel().map(c -> renameMapped(c, newName)));
            case QueryField.QueryServiceRecordField f -> new QueryField.QueryServiceRecordField(
                f.parentTypeName(), f.name(), f.location(), f.returnType(), f.serviceMethodCall(), f.errorChannel().map(c -> renameMapped(c, newName)));
            case ChildField.ServiceTableField f -> new ChildField.ServiceTableField(
                f.parentTypeName(), f.name(), f.location(), f.returnType(), f.joinPath(), f.filters(),
                f.orderBy(), f.pagination(), f.method(), f.sourceKey(), f.keySource(), f.loaderRegistration(), f.errorChannel().map(c -> renameRouted(c, newName)));
            case ChildField.ServiceRecordField f -> new ChildField.ServiceRecordField(
                f.parentTypeName(), f.name(), f.location(), f.returnType(), f.joinPath(), f.method(),
                f.sourceKey(), f.keySource(), f.loaderRegistration(), f.errorChannel().map(c -> renameRouted(c, newName)));
            case MutationField.MutationDmlRecordField f -> new MutationField.MutationDmlRecordField(
                f.parentTypeName(), f.name(), f.location(), f.returnType(), f.write(),
                f.errorChannel().map(c -> renameRouted(c, newName)));
            case MutationField.MutationBulkDmlRecordField f -> new MutationField.MutationBulkDmlRecordField(
                f.parentTypeName(), f.name(), f.location(), f.returnType(), f.write(),
                f.errorChannel().map(c -> renameRouted(c, newName)));
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
     * variant tag, the {@code @error} type name, the criteria and the optional matches. Identical
     * handler lists across different channels for the same payload class produce the same hash.
     *
     * <p>An authored {@code description:} is deliberately not part of the fingerprint. Every line
     * already carries the {@code @error} type name, and an {@code @error} type name determines its
     * whole handler list (the {@code ErrorIndex} fixed point resolves every union member through a
     * name-keyed map), so two channels whose lines differ only in a description are not
     * constructible and including it could never change a digest, a suffix, or an emitted name.
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
                + "|" + eh.matches().orElse("");
            case SqlStateHandler sh -> "S|" + errorTypeName + "|" + sh.sqlState()
                + "|" + sh.matches().orElse("");
            case VendorCodeHandler vh -> "V|" + errorTypeName + "|" + vh.vendorCode()
                + "|" + vh.matches().orElse("");
            case ValidationHandler ignored -> "L|" + errorTypeName + "||";
        };
    }
}
