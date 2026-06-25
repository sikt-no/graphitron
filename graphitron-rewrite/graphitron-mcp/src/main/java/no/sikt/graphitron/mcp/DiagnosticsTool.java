package no.sikt.graphitron.mcp;

import graphql.language.SourceLocation;
import io.modelcontextprotocol.spec.McpSchema;
import no.sikt.graphitron.rewrite.ValidationReport;
import no.sikt.graphitron.rewrite.catalog.LspSchemaSnapshot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * R368 slice 5 — the {@code diagnostics} read tool: the current validation errors and warnings off
 * {@code Workspace.validationReport()}, closing the authoring loop (an agent edits, then reads its
 * own diagnostics back). Pure read projection of already-classified data (the validation report's
 * typed rejections); no new validate-time arm.
 *
 * <p>Reports the live snapshot's availability / freshness axes alongside (R361 D3), so an agent can
 * tell whether the diagnostics are current relative to the schema it just read, without a
 * consistency lock.
 */
final class DiagnosticsTool {

    private DiagnosticsTool() {}

    /** Default page size: diagnostics can be large on a broken schema, so they page like the rest. */
    static final int DEFAULT_LIMIT = 100;

    static McpSchema.CallToolResult diagnosticsResult(
        ValidationReport report, LspSchemaSnapshot snapshot, Map<String, Object> args
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
                m.put("severity", "warning");
                m.put("message", w.message());
                addLocation(m, w.location());
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
}
