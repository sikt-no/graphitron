package no.sikt.graphitron.mcp;

import io.modelcontextprotocol.spec.McpSchema;
import no.sikt.graphitron.model.tables.records.DiagnosticRecord;
import no.sikt.graphitron.rewrite.RejectionKind;
import no.sikt.graphitron.rewrite.catalog.LspSchemaSnapshot;
import org.jooq.Condition;
import org.jooq.DSLContext;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static no.sikt.graphitron.model.Tables.DIAGNOSTIC;

/**
 * The {@code diagnostics} read tool: the current diagnostics as a projection of the fact
 * store's {@code diagnostic} union view, closing the authoring loop (an agent edits, then reads
 * its own diagnostics back). The view already unions the five arms (the rejection residue, the
 * store-native claim-conflict pilot, the lint and advisory arms, the compile oracle), so this
 * tool maps rows to the wire and never re-derives a classification; the {@code source}
 * discriminator ({@code "schema"} / {@code "compile"}) is the view's own column.
 *
 * <p>Filtering shares one null-safe {@code where} translation with {@code diagnostics.aggregate}
 * ({@link DiagnosticFacets#conditions}), which is what makes an aggregate group's key the exact
 * drill-down filter for this tool: the same columns, the same {@code IS NOT DISTINCT FROM}
 * comparisons, so the two tools cannot disagree about a group's membership. The {@code severity}
 * and {@code coordinate} arguments stay as sugar over the same mechanism.
 *
 * <p>Reads go through the session's store handle and are scoped to the session's graph; a
 * server booted without the handle refuses rather than answering an empty list that would read
 * as a clean schema. Reports the live snapshot's availability / freshness axes alongside, so an
 * agent can tell whether the diagnostics are current relative to the schema it just read.
 */
final class DiagnosticsTool {

    private DiagnosticsTool() {}

    /** Default page size: diagnostics can be large on a broken schema, so they page like the rest. */
    static final int DEFAULT_LIMIT = 100;

    static McpSchema.CallToolResult diagnosticsResult(
        GraphitronMcpServer.StoreHandle store, LspSchemaSnapshot snapshot, Map<String, Object> args
    ) {
        if (store == null) {
            return DiagnosticFacets.refusal("diagnostics");
        }
        try {
            return list(store.dsl(), store.graphName(), snapshot, args);
        } catch (DiagnosticFacets.BadRequest e) {
            return DiagnosticFacets.error("diagnostics: " + e.getMessage());
        }
    }

    private static McpSchema.CallToolResult list(
        DSLContext dsl, String graphName, LspSchemaSnapshot snapshot, Map<String, Object> args
    ) {
        Optional<String> severity = McpWire.stringArg(args, "severity");
        Optional<String> coordinate = McpWire.stringArg(args, "coordinate");
        List<Condition> conditions = DiagnosticFacets.conditions(graphName, args);
        severity.ifPresent(s -> conditions.add(
            DiagnosticFacets.Dimension.SEVERITY.matches(s.toLowerCase(Locale.ROOT))));
        coordinate.ifPresent(c -> conditions.add(DiagnosticFacets.Dimension.COORDINATE.matches(c)));

        var entries = dsl.selectFrom(DIAGNOSTIC)
            .where(conditions)
            .orderBy(DIAGNOSTIC.SOURCE.asc(),
                DIAGNOSTIC.FILE.asc().nullsLast(),
                DIAGNOSTIC.SOURCE_LINE.asc().nullsLast(),
                DIAGNOSTIC.SOURCE_COLUMN.asc().nullsLast(),
                DIAGNOSTIC.COORDINATE.asc().nullsLast(),
                DIAGNOSTIC.MESSAGE.asc())
            .fetch(DiagnosticsTool::entry);

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
     * Maps one view row onto the wire entry. The shape is the tool's shipped vocabulary
     * unchanged: {@code rejectionKind} renders the stored {@link RejectionKind} name in its
     * kebab-case display form and appears only on rejection-bearing rows, {@code lintRule} only
     * on lint rows, and the location (the view's canonical file URI plus its 1-based position
     * mapped to the 0-based wire shape every goto-definition consumer reads) only when the row
     * has one.
     */
    private static Map<String, Object> entry(DiagnosticRecord row) {
        var entry = new LinkedHashMap<String, Object>();
        entry.put("source", row.getSource());
        entry.put("severity", row.getSeverity());
        McpWire.putIfNotNull(entry, "coordinate", row.getCoordinate());
        entry.put("message", row.getMessage());
        if (row.getKind() != null) {
            entry.put("rejectionKind", RejectionKind.valueOf(row.getKind()).displayName());
        }
        McpWire.putIfNotNull(entry, "lintRule", row.getLintRule());
        if (row.getFile() != null) {
            var location = new LinkedHashMap<String, Object>();
            location.put("uri", row.getFile());
            location.put("line", row.getSourceLine() == null ? 0 : Math.max(row.getSourceLine() - 1, 0));
            location.put("column", row.getSourceColumn() == null ? 0 : Math.max(row.getSourceColumn() - 1, 0));
            entry.put("location", location);
        }
        return entry;
    }
}
