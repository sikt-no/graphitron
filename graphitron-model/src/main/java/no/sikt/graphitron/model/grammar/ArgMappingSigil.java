package no.sikt.graphitron.model.grammar;

import no.sikt.graphitron.model.selection.GraphQLSelectionParser;
import no.sikt.graphitron.model.selection.ParsedEntry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared owner of the {@code argMapping} sigil vocabulary: {@code $session} binds a Java
 * parameter to the session handle the {@code <sessionState>} mount returned, and
 * {@code $source.<column>} binds a routine parameter to a column of the row the routine is
 * applied to. One owner in the
 * {@code FieldSourceSigil} shape (the literal set, the parse fork, the admitted-site predicate,
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

    /**
     * Sigil prefix of the member form. An entry's right-hand side {@code $session.<member>} binds
     * the parameter to one readable member of the session handle, exactly one name after the dot.
     */
    public static final String SESSION_MEMBER_PREFIX = "$session.";

    /**
     * Sigil prefix. An entry's right-hand side {@code $source.<column>} binds the parameter to that
     * column of the row the routine is applied to: the parent's row where the routine comes first
     * in its path, the table the preceding {@code @reference} reached otherwise. A bare name never
     * binds a column.
     */
    public static final String SOURCE_PREFIX = "$source.";

    /**
     * Sigil literal. At an {@code @outbox} entry's right-hand side, binds the payload key to the
     * field's success outcome: what the write produced, and for a field returning an id, that id.
     */
    public static final String RESULT_LITERAL = "$result";

    /**
     * Sigil prefix of the path form. {@code $result.<path>} binds the key to a member of the
     * outcome; the path is one or more dot-separated names.
     */
    public static final String RESULT_PREFIX = "$result.";

    /**
     * Sigil prefix. {@code $scope.<member>} binds the key to one member of the field's scope: the
     * coordinate, the operation name, the alias, or the tenant. Exactly one name after the dot.
     */
    public static final String SCOPE_PREFIX = "$scope.";

    private ArgMappingSigil() {}

    /**
     * One lifted sigil entry's right-hand side, parsed once so every consumer switches on the
     * value instead of re-reading the string: a member cannot silently reach one emitter and not
     * the other. {@link #raw()} is the right-hand side as written, for diagnostics and fact capture.
     */
    public sealed interface Binding permits Binding.SessionHandle, Binding.SessionMember, Binding.SourceColumn,
            Binding.ResultRoot, Binding.ResultPath, Binding.ScopeMember {
        String raw();

        /** {@code $session}: the whole session handle. */
        record SessionHandle() implements Binding {
            @Override public String raw() { return SESSION_LITERAL; }
        }

        /** {@code $session.<member>}: one readable member of the session handle. */
        record SessionMember(String member) implements Binding {
            @Override public String raw() { return SESSION_MEMBER_PREFIX + member; }
        }

        /** {@code $source.<column>}: a column of the row a routine is applied to. */
        record SourceColumn(String column) implements Binding {
            @Override public String raw() { return SOURCE_PREFIX + column; }
        }

        /** {@code $result}: the field's success outcome as a whole. */
        record ResultRoot() implements Binding {
            @Override public String raw() { return RESULT_LITERAL; }
        }

        /** {@code $result.<path>}: a member of the outcome, one or more dot-separated names. */
        record ResultPath(List<String> segments) implements Binding {
            public ResultPath {
                segments = List.copyOf(segments);
            }
            @Override public String raw() { return RESULT_PREFIX + String.join(".", segments); }
        }

        /** {@code $scope.<member>}: one member of the field's scope. */
        record ScopeMember(String member) implements Binding {
            @Override public String raw() { return SCOPE_PREFIX + member; }
        }
    }

    /**
     * The argMapping sites, for the per-site admission rule: {@code $session} (whole handle or one
     * member) is admitted at the {@code @service} and {@code @condition} argMappings; every other
     * site rejects it naming the admitted ones (the {@code $source} precedent exactly).
     * {@link #REFERENCE_STEP}, the path-step condition, is not one of them.
     */
    public enum Site {
        SERVICE("@service argMapping"),
        CONDITION("@condition argMapping"),
        REFERENCE_STEP("a @reference/@referenceFor path condition's argMapping"),
        ROUTINE("@routine argMapping"),
        RECORD("@record argMapping"),
        EXTERNAL_FIELD("@externalField argMapping"),
        ENUM("@enum argMapping"),
        OUTBOX("@outbox argMapping");

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
            return this == SERVICE || this == CONDITION;
        }

        /** True only at the routine site, the one whose call reads the row it is applied to. */
        public boolean admitsSourceSigil() {
            return this == ROUTINE;
        }

        /** True only at the outbox site, the one whose payload describes what a write produced. */
        public boolean admitsResultSigil() {
            return this == OUTBOX;
        }

        /** True only at the outbox site, the one whose payload describes the field it was written on. */
        public boolean admitsScopeSigil() {
            return this == OUTBOX;
        }
    }

    /**
     * Outcome of {@link #scan}: the recognized sigil entries lifted out (Java parameter name to
     * parsed {@link Binding}, document order) plus the residual raw string for {@code parseEntries}, or
     * the canonical rejection.
     *
     * <p>{@code sigilPositions} carries each lifted entry's 0-based position in the argMapping as
     * the author wrote it. The lift removes entries from the middle of the list, so the residual's
     * own numbering is not the document's; a caller that records a position (fact capture does,
     * the position being part of an entry's key) reconstructs document order by taking these
     * positions for the sigils and the remaining ones, in order, for the residual.
     */
    public sealed interface ScanResult permits ScanResult.Ok, ScanResult.Rejected {
        record Ok(Map<String, Binding> sigilBindings, Map<String, Integer> sigilPositions,
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
        var sigils = new LinkedHashMap<String, Binding>();
        var positions = new LinkedHashMap<String, Integer>();
        var residual = new StringBuilder();
        int index = -1;
        for (String piece : raw.split(",", -1)) {
            index++;
            int colon = piece.indexOf(':');
            String rhs = colon < 0 ? "" : piece.substring(colon + 1).strip();
            if (colon >= 0 && rhs.startsWith("$")) {
                Binding binding;
                if (SESSION_LITERAL.equals(rhs)) {
                    if (!site.admitsSessionSigil()) {
                        return new ScanResult.Rejected(notAdmittedMessage(site));
                    }
                    binding = new Binding.SessionHandle();
                } else if (rhs.startsWith(SESSION_MEMBER_PREFIX)) {
                    if (!site.admitsSessionSigil()) {
                        return new ScanResult.Rejected(notAdmittedMessage(site));
                    }
                    String member = sessionMember(rhs);
                    if (member == null) {
                        return new ScanResult.Rejected(deepSessionPathMessage(rhs));
                    }
                    binding = new Binding.SessionMember(member);
                } else if (sourceColumn(rhs) != null) {
                    if (!site.admitsSourceSigil()) {
                        return new ScanResult.Rejected(sourceNotAdmittedMessage(site));
                    }
                    binding = new Binding.SourceColumn(sourceColumn(rhs));
                } else if (RESULT_LITERAL.equals(rhs)) {
                    if (!site.admitsResultSigil()) {
                        return new ScanResult.Rejected(outboxSigilNotAdmittedMessage(rhs, site));
                    }
                    binding = new Binding.ResultRoot();
                } else if (rhs.startsWith(RESULT_PREFIX)) {
                    if (!site.admitsResultSigil()) {
                        return new ScanResult.Rejected(outboxSigilNotAdmittedMessage(rhs, site));
                    }
                    List<String> path = resultPath(rhs);
                    if (path == null) {
                        return new ScanResult.Rejected(malformedResultPathMessage(rhs));
                    }
                    binding = new Binding.ResultPath(path);
                } else if (rhs.startsWith(SCOPE_PREFIX)) {
                    if (!site.admitsScopeSigil()) {
                        return new ScanResult.Rejected(outboxSigilNotAdmittedMessage(rhs, site));
                    }
                    String member = scopeMember(rhs);
                    if (member == null) {
                        return new ScanResult.Rejected(malformedScopeMemberMessage(rhs));
                    }
                    binding = new Binding.ScopeMember(member);
                } else {
                    return new ScanResult.Rejected(unknownSigilMessage(rhs));
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
                sigils.put(javaName, binding);
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

    /** One entry of an argMapping as written: the parameter it names and what it binds it to. */
    public record Entry(String parameter, String boundTo) {}

    /**
     * Every entry of {@code raw} in the order it was written, judging nothing about the site.
     *
     * <p>The reading an argMapping has wherever it is written, which {@link #scan} is not: that
     * fuses this with which site admits a sigil, a rule about the application rather than about the
     * string. A {@code $}-prefixed right-hand side is lifted verbatim before the rest is tokenized,
     * for the reason {@link #scan} gives, and is an entry like any other. Empty or blank is no
     * entries, which is identity for every parameter.
     *
     * @throws no.sikt.graphitron.model.selection.GraphQLSelectionParseException where the residual
     *         does not parse
     */
    public static List<Entry> entries(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        var lifted = new LinkedHashMap<Integer, Entry>();
        var residual = new StringBuilder();
        String[] pieces = raw.split(",", -1);
        for (int index = 0; index < pieces.length; index++) {
            String piece = pieces[index];
            int colon = piece.indexOf(':');
            String rhs = colon < 0 ? "" : piece.substring(colon + 1).strip();
            if (colon >= 0 && rhs.startsWith("$")) {
                lifted.put(index, new Entry(piece.substring(0, colon).strip(), rhs));
                continue;
            }
            if (!residual.isEmpty()) {
                residual.append(',');
            }
            residual.append(piece);
        }
        var parsed = GraphQLSelectionParser.parseEntries(residual.toString()).iterator();
        var entries = new ArrayList<Entry>();
        for (int index = 0; index < pieces.length; index++) {
            if (lifted.containsKey(index)) {
                entries.add(lifted.get(index));
            } else if (parsed.hasNext()) {
                ParsedEntry entry = parsed.next();
                entries.add(new Entry(entry.key(), String.join(".", entry.segments())));
            }
        }
        return entries;
    }

    /**
     * The column a {@code $source.<column>} right-hand side names, or {@code null} where
     * {@code rhs} is not that sigil with exactly one name after it.
     */
    public static String sourceColumn(String rhs) {
        if (rhs == null || !rhs.startsWith(SOURCE_PREFIX)) {
            return null;
        }
        String column = rhs.substring(SOURCE_PREFIX.length());
        return column.isEmpty() || column.indexOf('.') >= 0 ? null : column;
    }

    /**
     * The member a {@code $session.<member>} right-hand side names, or {@code null} where
     * {@code rhs} is not that sigil with exactly one non-empty name after the dot.
     */
    public static String sessionMember(String rhs) {
        if (rhs == null || !rhs.startsWith(SESSION_MEMBER_PREFIX)) {
            return null;
        }
        String member = rhs.substring(SESSION_MEMBER_PREFIX.length());
        return member.isEmpty() || member.indexOf('.') >= 0 ? null : member;
    }

    /**
     * The names a {@code $result.<path>} right-hand side walks, or {@code null} where {@code rhs} is
     * not that sigil with one or more non-empty dot-separated names after the prefix.
     */
    public static List<String> resultPath(String rhs) {
        if (rhs == null || !rhs.startsWith(RESULT_PREFIX)) {
            return null;
        }
        String[] segments = rhs.substring(RESULT_PREFIX.length()).split("\\.", -1);
        for (String segment : segments) {
            if (segment.isEmpty()) {
                return null;
            }
        }
        return List.of(segments);
    }

    /**
     * The member a {@code $scope.<member>} right-hand side names, or {@code null} where {@code rhs}
     * is not that sigil with exactly one non-empty name after the dot.
     */
    public static String scopeMember(String rhs) {
        if (rhs == null || !rhs.startsWith(SCOPE_PREFIX)) {
            return null;
        }
        String member = rhs.substring(SCOPE_PREFIX.length());
        return member.isEmpty() || member.indexOf('.') >= 0 ? null : member;
    }

    /** Canonical message for a {@code $result.} path with an empty segment. */
    public static String malformedResultPathMessage(String raw) {
        return "'" + raw + "' is not a result binding; the form is " + RESULT_LITERAL + " or "
            + RESULT_PREFIX + "<name>[." + "<name>...], with no empty name between the dots.";
    }

    /** Canonical message for a {@code $scope.} value that is not exactly one member name. */
    public static String malformedScopeMemberMessage(String raw) {
        return "'" + raw + "' is not a scope binding; the form is " + SCOPE_PREFIX
            + "<member>, exactly one member name after the dot.";
    }

    /** Canonical message for {@code $result} or {@code $scope.<member>} at a site that does not admit it. */
    public static String outboxSigilNotAdmittedMessage(String raw, Site site) {
        return "'" + raw + "' is not admitted in " + site.description()
            + "; " + RESULT_LITERAL + " and " + SCOPE_PREFIX + "<member> are only valid in @outbox"
            + " argMapping, where they bind a payload key to what the write produced or to the"
            + " field's scope.";
    }

    /** Canonical message for a {@code $session.} path that is not exactly one member name. */
    public static String deepSessionPathMessage(String raw) {
        return "'" + raw + "' is not a session member binding; the form is " + SESSION_MEMBER_PREFIX
            + "<member>, exactly one member name after the dot (a nested path is not supported).";
    }

    /** Canonical message for a {@code $}-prefixed argMapping value that is not an admitted sigil. */
    public static String unknownSigilMessage(String raw) {
        return "Unknown sigil '" + raw + "' in argMapping; allowed: " + SESSION_LITERAL + ", "
            + SESSION_MEMBER_PREFIX + "<member>, " + SOURCE_PREFIX + "<column>, " + RESULT_LITERAL
            + ", " + RESULT_PREFIX + "<path>, " + SCOPE_PREFIX + "<member>";
    }

    /** Canonical message for {@code $source.<column>} at an argMapping site that does not admit it. */
    public static String sourceNotAdmittedMessage(Site site) {
        return "'" + SOURCE_PREFIX + "<column>' is not admitted in " + site.description()
            + "; it is only valid in @routine argMapping, where it binds the parameter to a column of"
            + " the row the routine is applied to.";
    }

    /** Canonical message for {@code $session} at an argMapping site that does not admit it. */
    public static String notAdmittedMessage(Site site) {
        return "'" + SESSION_LITERAL + "' is not admitted in " + site.description()
            + "; it is only valid in @service and @condition argMapping, where it binds the Java"
            + " parameter to the session handle the <sessionState> mount returned, or to one member"
            + " of it as " + SESSION_MEMBER_PREFIX + "<member>.";
    }
}
