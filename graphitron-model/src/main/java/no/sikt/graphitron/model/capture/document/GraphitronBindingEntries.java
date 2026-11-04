package no.sikt.graphitron.model.capture.document;

import graphql.language.Directive;
import no.sikt.graphitron.model.sink.BindBatch;
import org.jooq.DSLContext;
import org.jooq.Rows;
import org.jooq.Table;

import java.time.LocalDateTime;
import java.util.List;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_BINDING_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_NODE_ID_ENTRY;
import static no.sikt.graphitron.model.capture.document.GraphitronAstEntries.applied;
import static no.sikt.graphitron.model.capture.document.GraphitronAstEntries.string;
import static no.sikt.graphitron.model.capture.document.GraphitronAstEntries.wrote;
import static org.jooq.impl.DSL.excluded;
import static org.jooq.impl.DSL.val;

/**
 * What a {@code @field} or {@code @nodeId} application wrote, wherever it was written.
 *
 * <p>Not a site's writer: the name a {@code @field} writes and the node type a {@code @nodeId}
 * writes are each one fact at every site the definition admits, so both are read off every
 * application at once and land in one relation each. Keyed by the application's own position, so
 * which element the directive was written on, and through it the site, is one join away.
 */
final class GraphitronBindingEntries {

    private GraphitronBindingEntries() {}

    /**
     * Makes {@code source}'s binding rows under {@code graph} be what {@code applications}, from
     * every site, now say.
     */
    static void write(DSLContext dsl, String graph, String source,
                      List<GraphQLAstEntries.Nested<Directive>> applications, LocalDateTime touchedAt) {
        bindings(dsl, graph, touchedAt, wrote(applied(applications, "field"), "name"));
        nodeIds(dsl, graph, touchedAt, applied(applications, "nodeId"));
        GraphitronAstEntries.sweep(dsl, graph, source, touchedAt, TABLES_TO_SWEEP);
    }

    /**
     * What the sweep deletes from, and the only thing that reads it. Listed rather than found by
     * prefix: a relation added above and not here would keep its stale rows silently.
     */
    private static final List<Table<?>> TABLES_TO_SWEEP = List.of(
        GRAPHITRON_AST_BINDING_ENTRY, GRAPHITRON_AST_NODE_ID_ENTRY);

    private static void bindings(DSLContext dsl, String graph, LocalDateTime touchedAt,
                                 List<Directive> applications) {
        var t = GRAPHITRON_AST_BINDING_ENTRY;
        var rows = applications.stream().collect(Rows.toRowList(
            application -> val(graph, t.GRAPH_NAME),
            application -> GraphQLAstEntries.sourceName(application),
            application -> GraphQLAstEntries.sourceLine(application),
            application -> GraphQLAstEntries.sourceColumn(application),
            application -> val(touchedAt, t.TOUCHED_AT),
            application -> val(string(application, "name"), t.NAME_REF)));
        BindBatch.execute(dsl, rows, markers ->
            dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN,
                    t.TOUCHED_AT, t.NAME_REF)
                .values(markers)
                .onDuplicateKeyUpdate()
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
                .set(t.NAME_REF, excluded(t.NAME_REF)));
    }

    private static void nodeIds(DSLContext dsl, String graph, LocalDateTime touchedAt,
                                List<Directive> applications) {
        var t = GRAPHITRON_AST_NODE_ID_ENTRY;
        var rows = applications.stream().collect(Rows.toRowList(
            application -> val(graph, t.GRAPH_NAME),
            application -> GraphQLAstEntries.sourceName(application),
            application -> GraphQLAstEntries.sourceLine(application),
            application -> GraphQLAstEntries.sourceColumn(application),
            application -> val(touchedAt, t.TOUCHED_AT),
            application -> val(string(application, "typeName"), t.NODE_TYPE_REF)));
        BindBatch.execute(dsl, rows, markers ->
            dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN,
                    t.TOUCHED_AT, t.NODE_TYPE_REF)
                .values(markers)
                .onDuplicateKeyUpdate()
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
                .set(t.NODE_TYPE_REF, excluded(t.NODE_TYPE_REF)));
    }
}
