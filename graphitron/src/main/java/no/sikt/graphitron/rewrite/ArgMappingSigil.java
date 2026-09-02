package no.sikt.graphitron.rewrite;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Shared owner of the {@code argMapping} sigil vocabulary: {@code $session} binds a Java
 * parameter to the session handle the {@code <sessionState>} mount returned. One owner in the
 * {@link FieldSourceSigil} shape (the literal set, the parse fork, the admitted-site predicate,
 * the canonical messages, unit-pinnable directly), routed through by both the build-side parse
 * ({@code ArgBindingMap.parseArgMapping}) and fact capture's {@code @service} argMapping site,
 * so the two cannot drift on what a sigil is.
 *
 * <p>The scan runs on the raw string <em>before</em> tokenization, because the shared
 * {@code GraphQLSelectionParser.parseEntries} lexes {@code $}-prefixed text as a
 * {@code VARIABLE} token and hard-rejects it as a value; sigil entries are lifted out here and
 * the residual entries delegate to {@code parseEntries} unchanged. GraphQL names cannot start
 * with {@code $}, so the sigil is lexically disjoint from argument and contextArgument names by
 * construction.
 */
public final class ArgMappingSigil {

    /**
     * Sigil literal. Authors write this exact value as an argMapping entry's right-hand side to
     * bind the Java parameter to the mount's returned session handle.
     */
    public static final String SESSION_LITERAL = "$session";

    private ArgMappingSigil() {}

    /**
     * The argMapping sites, for the per-site admission rule: {@code $session} is admitted at the
     * {@code @service} argMapping in v1; every other site rejects it naming the admitted one
     * (the {@code $source} precedent exactly). {@code columnMapping} never routes through this
     * owner at all: no sigil is admitted there, and a {@code $} there keeps its parse rejection.
     */
    public enum Site {
        SERVICE("@service argMapping"),
        CONDITION("@condition argMapping"),
        REFERENCE_STEP("a @reference/@referenceFor path condition's argMapping"),
        ROUTINE("@routine argMapping"),
        RECORD("@record argMapping"),
        EXTERNAL_FIELD("@externalField argMapping"),
        ENUM("@enum argMapping");

        private final String description;

        Site(String description) {
            this.description = description;
        }

        /** The site named in the non-admission message. */
        public String description() {
            return description;
        }

        /** True only at the admitted site; parse, diagnostics and completions read one predicate. */
        public boolean admitsSessionSigil() {
            return this == SERVICE;
        }
    }

    /**
     * Outcome of {@link #scan}: the recognized sigil entries lifted out (Java parameter name to
     * sigil literal, document order) plus the residual raw string for {@code parseEntries}, or
     * the canonical rejection.
     *
     * <p>{@code sigilPositions} carries each lifted entry's 0-based position in the argMapping as
     * the author wrote it. The lift removes entries from the middle of the list, so the residual's
     * own numbering is not the document's; a caller that records a position (fact capture does,
     * the position being part of an entry's key) reconstructs document order by taking these
     * positions for the sigils and the remaining ones, in order, for the residual.
     */
    public sealed interface ScanResult permits ScanResult.Ok, ScanResult.Rejected {
        record Ok(Map<String, String> sigilBindings, Map<String, Integer> sigilPositions,
                  String residual) implements ScanResult {
            public Ok {
                sigilBindings = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(sigilBindings));
                sigilPositions = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(sigilPositions));
            }
        }
        record Rejected(String message) implements ScanResult {}
    }

    /**
     * Scans a raw argMapping string for entries whose right-hand side is {@code $}-prefixed,
     * before tokenization. A recognized sigil at the admitted site is lifted into the binding
     * map; an unknown sigil rejects naming the allowed set; a known sigil at a non-admitted site
     * rejects naming the admitted one. Entries without a sigil pass through to the residual
     * verbatim (including anything malformed, which keeps its ordinary parse rejection).
     */
    public static ScanResult scan(String raw, Site site) {
        if (raw == null || raw.isBlank() || raw.indexOf('$') < 0) {
            return new ScanResult.Ok(Map.of(), Map.of(), raw);
        }
        var sigils = new LinkedHashMap<String, String>();
        var positions = new LinkedHashMap<String, Integer>();
        var residual = new StringBuilder();
        int index = -1;
        for (String piece : raw.split(",", -1)) {
            index++;
            int colon = piece.indexOf(':');
            String rhs = colon < 0 ? "" : piece.substring(colon + 1).strip();
            if (colon >= 0 && rhs.startsWith("$")) {
                if (!SESSION_LITERAL.equals(rhs)) {
                    return new ScanResult.Rejected(unknownSigilMessage(rhs));
                }
                if (!site.admitsSessionSigil()) {
                    return new ScanResult.Rejected(notAdmittedMessage(site));
                }
                String javaName = piece.substring(0, colon).strip();
                if (javaName.isEmpty()) {
                    return new ScanResult.Rejected(
                        "argMapping entry ': " + rhs + "' names no Java parameter to bind");
                }
                if (sigils.containsKey(javaName)) {
                    return new ScanResult.Rejected(
                        "argMapping has duplicate entries for Java parameter '" + javaName
                            + "' — each Java parameter may appear at most once");
                }
                sigils.put(javaName, rhs);
                positions.put(javaName, index);
                continue;
            }
            if (!residual.isEmpty()) {
                residual.append(',');
            }
            residual.append(piece);
        }
        return new ScanResult.Ok(sigils, positions, residual.toString());
    }

    /** Canonical message for a {@code $}-prefixed argMapping value that is not an admitted sigil. */
    public static String unknownSigilMessage(String raw) {
        return "Unknown sigil '" + raw + "' in argMapping; allowed: " + SESSION_LITERAL;
    }

    /** Canonical message for {@code $session} at an argMapping site that does not admit it. */
    public static String notAdmittedMessage(Site site) {
        return "'" + SESSION_LITERAL + "' is not admitted in " + site.description()
            + "; it is only valid in @service argMapping, where it binds the Java parameter to the"
            + " session handle the <sessionState> mount returned.";
    }
}
