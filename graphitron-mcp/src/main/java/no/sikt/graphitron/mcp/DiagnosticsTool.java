package no.sikt.graphitron.mcp;

import graphql.language.SourceLocation;
import io.modelcontextprotocol.spec.McpSchema;
import no.sikt.graphitron.rewrite.ValidationReport;
import no.sikt.graphitron.rewrite.catalog.LspSchemaSnapshot;
import no.sikt.graphitron.rewrite.compile.CompileDiagnostic;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The {@code diagnostics} read tool: the current validation errors and warnings off
 * {@code Workspace.validationReport()}, closing the authoring loop (an agent edits, then reads its
 * own diagnostics back). Pure read projection of already-classified data (the validation report's
 * typed rejections); no new validate-time arm.
 *
 * <p>The {@code graphitron:dev} incremental-compile round's diagnostics are folded in off
 * {@code Workspace.compileDiagnostics()}. Every entry now carries a {@code source} discriminator:
 * {@code "schema"} for the validator rejections, {@code "compile"} for generated-code javac errors.
 * These are separate channels by design (a generated-file javac error has no schema coordinate to
 * fabricate), unioned here so an agent editing through MCP reads both back in the one tool it polls.
 *
 * <p>Reports the live snapshot's availability / freshness axes alongside, so an agent can
 * tell whether the diagnostics are current relative to the schema it just read, without a
 * consistency lock.
 */
final class DiagnosticsTool {

    private DiagnosticsTool() {}

    /** Default page size: diagnostics can be large on a broken schema, so they page like the rest. */
    static final int DEFAULT_LIMIT = 100;

    static McpSchema.CallToolResult diagnosticsResult(
        ValidationReport report, List<CompileDiagnostic> compileDiagnostics,
        LspSchemaSnapshot snapshot, Map<String, Object> args
    ) {
        Optional<String> severity = McpWire.stringArg(args, "severity");
        Optional<String> coordinate = McpWire.stringArg(args, "coordinate");
        boolean wantError = severity.map(s -> s.equalsIgnoreCase("error")).orElse(true);
        boolean wantWarning = severity.map(s -> s.equalsIgnoreCase("warning")).orElse(true);

        var entries = new ArrayList<Map<String, Object>>();
        if (wantError) {
            for (var e : report.errors()) {
                if (coordinate.isPresent()
                    && (e.coordinate() == null || !e.coordinate().equals(coordinate.get()))) {
                    continue;
                }
                var m = new LinkedHashMap<String, Object>();
                m.put("source", "schema");
                m.put("severity", "error");
                McpWire.putIfNotNull(m, "coordinate", e.coordinate());
                m.put("message", e.message());
                m.put("rejectionKind", e.kind().displayName());
                addLocation(m, e.location());
                entries.add(m);
            }
        }
        if (wantWarning) {
            for (var w : report.warnings()) {
                // Warnings carry no coordinate; a coordinate filter excludes them by construction.
                if (coordinate.isPresent()) continue;
                var m = new LinkedHashMap<String, Object>();
                m.put("source", "schema");
                m.put("severity", "warning");
                m.put("message", w.message());
                // A lint finding (the sealed BuildWarning's tagged arm) carries a typed LintRule;
                // project its stable id so an agent sees which rule fired, not just the message.
                // The no-rule arm carries no id, so there is no nullable field to guard.
                if (w instanceof no.sikt.graphitron.rewrite.BuildWarning.LintFinding lf) {
                    m.put("lintRule", lf.rule().id());
                }
                addLocation(m, w.location());
                entries.add(m);
            }
        }
        // Generated-code compile diagnostics. They carry no schema coordinate, so a coordinate
        // filter excludes them by construction, matching how warnings are handled. javac's ERROR kind
        // maps to the "error" severity gate; every other kind (WARNING / MANDATORY_WARNING / NOTE)
        // maps to "warning".
        if (coordinate.isEmpty()) {
            for (var d : compileDiagnostics) {
                boolean isError = "ERROR".equals(d.severity());
                if (isError ? !wantError : !wantWarning) {
                    continue;
                }
                var m = new LinkedHashMap<String, Object>();
                m.put("source", "compile");
                m.put("severity", isError ? "error" : "warning");
                m.put("message", d.message());
                addCompileLocation(m, d);
                entries.add(m);
            }
        }

        var paged = McpWire.page(entries, args, DEFAULT_LIMIT);
        var fields = new LinkedHashMap<String, Object>();
        fields.put("diagnostics", paged.items());
        paged.nextCursor().ifPresent(c -> fields.put("nextCursor", c));
        McpWire.writeSnapshotAxes(fields, snapshot);

        String summary = "diagnostics: " + entries.size() + " entr(ies)"
            + severity.map(s -> " of severity '" + s + "'").orElse("")
            + coordinate.map(co -> " at '" + co + "'").orElse("")
            + "; showing " + paged.items().size()
            + (paged.nextCursor().isPresent() ? " (more available)" : "") + ".";
        return McpSchema.CallToolResult.builder()
            .addTextContent(summary)
            .structuredContent(fields)
            .build();
    }

    /**
     * Maps a graphql-java {@link SourceLocation} (1-based line/column) onto the {@code {uri, line,
     * column}} wire shape every goto-definition consumer reads (0-based, mirroring the {@code -1}
     * adjustment elsewhere). Omits the location when there is no usable source name.
     */
    private static void addLocation(Map<String, Object> entry, SourceLocation loc) {
        if (loc == null || loc.getSourceName() == null || loc.getSourceName().isEmpty()) return;
        var m = new LinkedHashMap<String, Object>();
        m.put("uri", ValidationReport.canonicalUri(loc.getSourceName()));
        m.put("line", Math.max(loc.getLine() - 1, 0));
        m.put("column", Math.max(loc.getColumn() - 1, 0));
        entry.put("location", m);
    }

    /**
     * Maps a {@link CompileDiagnostic}'s generated-{@code .java} anchor onto the same {@code {uri, line,
     * column}} wire shape, 0-based like {@link #addLocation}. javac reports 1-based line/column and
     * {@link javax.tools.Diagnostic#NOPOS} as {@code -1}; both clamp to {@code 0}. The file is a plain
     * path (a generated {@code .java}), surfaced as the {@code uri} field unchanged.
     */
    private static void addCompileLocation(Map<String, Object> entry, CompileDiagnostic diagnostic) {
        if (diagnostic.file() == null || diagnostic.file().isEmpty()) return;
        var m = new LinkedHashMap<String, Object>();
        m.put("uri", diagnostic.file());
        // Cast to int so the wire shape matches the schema-location branch (which reads int line/column);
        // javac positions are line/column offsets, well within int range.
        m.put("line", (int) Math.max(diagnostic.line() - 1, 0));
        m.put("column", (int) Math.max(diagnostic.column() - 1, 0));
        entry.put("location", m);
    }
}
