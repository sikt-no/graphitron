package no.sikt.graphitron.model.capture.document;

import graphql.language.Directive;
import no.sikt.graphitron.model.sink.BindBatch;
import org.jooq.DSLContext;
import org.jooq.Rows;
import org.jooq.Table;

import java.time.LocalDateTime;
import java.util.List;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_INDEX_ENTRY;
import static no.sikt.graphitron.model.capture.document.GraphitronAstEntries.applied;
import static no.sikt.graphitron.model.capture.document.GraphitronAstEntries.string;
import static no.sikt.graphitron.model.capture.document.GraphitronAstEntries.wrote;
import static org.jooq.impl.DSL.excluded;
import static org.jooq.impl.DSL.val;

/**
 * The enum-value site's half of the decode: what a directive written on an enum value meant.
 *
 * <p>Keyed by the application's own position, which is
 * {@code graphql_ast_directive_application_entry}'s key, so a row here is the decode of exactly one
 * row there. Which value the directive was written on, and in which file, is one join away rather
 * than a column.
 *
 * <p>Three directive names reach this site and every one carries an argument, which is unusual and
 * follows from what the site is for. An enum value carrying a graphitron directive is a sorting
 * vocabulary rather than a schema element the generator emits, so every directive admitted here
 * exists to carry an argument and none of them is a marker. One gets a relation here, the deprecated
 * {@code @index}. {@code @order} writes the vocabulary {@code @defaultOrder} writes at the field site,
 * so both are one decode, {@link GraphitronOrderEntries}; the name a {@code @field} writes is one
 * fact at every site, written by {@link GraphitronBindingEntries}.
 *
 * @see GraphitronAstEntries for what every site's decode holds in common
 */
final class GraphitronEnumValueEntries {

    private GraphitronEnumValueEntries() {}

    /**
     * Makes {@code source}'s enum-value rows under {@code graph} be what {@code document}'s
     * applications now say.
     */
    static void write(DSLContext dsl, String graph, String source,
                      List<GraphQLAstEntries.Nested<Directive>> applications, LocalDateTime touchedAt) {
        indexes(dsl, graph, touchedAt, wrote(applied(applications, "index"), "name"));

        GraphitronAstEntries.sweep(dsl, graph, source, touchedAt, TABLES_TO_SWEEP);
    }

    /**
     * What the sweep deletes from, and the only thing that reads it. Listed rather than found by
     * prefix, on the terms {@link GraphitronAstEntries#sweep} states: a relation this writer gained
     * and did not list would keep its stale rows silently.
     */
    private static final List<Table<?>> TABLES_TO_SWEEP = List.of(GRAPHITRON_AST_INDEX_ENTRY);

    private static void indexes(DSLContext dsl, String graph, LocalDateTime touchedAt,
                                List<Directive> applications) {
        var t = GRAPHITRON_AST_INDEX_ENTRY;
        var rows = applications.stream().collect(Rows.toRowList(
            application -> val(graph, t.GRAPH_NAME),
            application -> GraphQLAstEntries.sourceName(application),
            application -> GraphQLAstEntries.sourceLine(application),
            application -> GraphQLAstEntries.sourceColumn(application),
            application -> val(touchedAt, t.TOUCHED_AT),
            application -> val(string(application, "name"), t.INDEX_REF)));
        BindBatch.execute(dsl, rows, markers ->
            dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN,
                    t.TOUCHED_AT, t.INDEX_REF)
                .values(markers)
                .onDuplicateKeyUpdate()
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
                .set(t.INDEX_REF, excluded(t.INDEX_REF)));
    }

}
