package no.sikt.graphitron.model.capture.document;

import graphql.language.Directive;
import no.sikt.graphitron.model.sink.BindBatch;
import org.jooq.DSLContext;
import org.jooq.Rows;
import org.jooq.Table;

import java.time.LocalDateTime;
import java.util.List;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_ENUM_VALUE_BINDING_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_INDEX_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_ORDER_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_ORDER_FIELD_ENTRY;
import static no.sikt.graphitron.model.capture.document.GraphitronAstEntries.applied;
import static no.sikt.graphitron.model.capture.document.GraphitronAstEntries.bool;
import static no.sikt.graphitron.model.capture.document.GraphitronAstEntries.elementsOf;
import static no.sikt.graphitron.model.capture.document.GraphitronAstEntries.inside;
import static no.sikt.graphitron.model.capture.document.GraphitronAstEntries.string;
import static no.sikt.graphitron.model.capture.document.GraphitronAstEntries.stringOf;
import static no.sikt.graphitron.model.capture.document.GraphitronAstEntries.wrote;
import static org.jooq.impl.DSL.excluded;
import static org.jooq.impl.DSL.val;

/**
 * The enum-value site's half of the decode: what a directive written on an enum value meant.
 *
 * <p>Keyed by the application's own position, which is
 * {@code graphql_ast_enum_value_directive_entry}'s key, so a row here is the decode of exactly one
 * row there. Which value the directive was written on, and in which file, is one join away rather
 * than a column.
 *
 * <p>Three directive names reach this site and all three get a relation, which is unusual and
 * follows from what the site is for. An enum value carrying a graphitron directive is a sorting
 * vocabulary rather than a schema element the generator emits, so every directive admitted here
 * exists to carry an argument and none of them is a marker.
 *
 * <p>{@code @order} is the enum-value twin of {@code @defaultOrder} at the field site, down to the
 * child relation its field list becomes, and the two are deliberately not one relation: the same
 * sorting vocabulary read at an enum value declares a surface an argument may later select, where
 * at a field it declares the surface that field falls back to. The site is already the key, so the
 * populations are told apart by which relation a row is in.
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
        bindings(dsl, graph, touchedAt, wrote(applied(applications, "field"), "name"));
        indexes(dsl, graph, touchedAt, wrote(applied(applications, "index"), "name"));

        var orders = applied(applications, "order");
        orders(dsl, graph, touchedAt, orders);
        orderFields(dsl, graph, touchedAt, orders);
        GraphitronAstEntries.sweep(dsl, graph, source, touchedAt, TABLES_TO_SWEEP);
    }

    /**
     * What the sweep deletes from, and the only thing that reads it. Listed rather than found by
     * prefix, on the terms {@link GraphitronAstEntries#sweep} states: a relation this writer gained
     * and did not list would keep its stale rows silently.
     */
    private static final List<Table<?>> TABLES_TO_SWEEP = List.of(
        GRAPHITRON_AST_ENUM_VALUE_BINDING_ENTRY, GRAPHITRON_AST_INDEX_ENTRY,
        GRAPHITRON_AST_ORDER_FIELD_ENTRY, GRAPHITRON_AST_ORDER_ENTRY);

    private static void bindings(DSLContext dsl, String graph, LocalDateTime touchedAt,
                                 List<Directive> applications) {
        var t = GRAPHITRON_AST_ENUM_VALUE_BINDING_ENTRY;
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

    /**
     * A row for every application and not only for those that wrote an argument, because the field
     * list is the child relation: an application that took that surface has both its own columns
     * null and still has to be here for its elements to hang off.
     */
    private static void orders(DSLContext dsl, String graph, LocalDateTime touchedAt,
                               List<Directive> applications) {
        var t = GRAPHITRON_AST_ORDER_ENTRY;
        var rows = applications.stream().collect(Rows.toRowList(
            application -> val(graph, t.GRAPH_NAME),
            application -> GraphQLAstEntries.sourceName(application),
            application -> GraphQLAstEntries.sourceLine(application),
            application -> GraphQLAstEntries.sourceColumn(application),
            application -> val(touchedAt, t.TOUCHED_AT),
            application -> val(string(application, "index"), t.INDEX_REF),
            application -> val(bool(application, "primaryKey"), t.PRIMARY_KEY)));
        BindBatch.execute(dsl, rows, markers ->
            dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN,
                    t.TOUCHED_AT, t.INDEX_REF, t.PRIMARY_KEY)
                .values(markers)
                .onDuplicateKeyUpdate()
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
                .set(t.INDEX_REF, excluded(t.INDEX_REF))
                .set(t.PRIMARY_KEY, excluded(t.PRIMARY_KEY)));
    }

    private static void orderFields(DSLContext dsl, String graph, LocalDateTime touchedAt,
                                    List<Directive> applications) {
        var t = GRAPHITRON_AST_ORDER_FIELD_ENTRY;
        var rows = elementsOf(applications, "fields").stream().collect(Rows.toRowList(
            element -> val(graph, t.GRAPH_NAME),
            element -> GraphQLAstEntries.sourceName(element.node()),
            element -> GraphQLAstEntries.sourceLine(element.node()),
            element -> GraphQLAstEntries.sourceColumn(element.node()),
            element -> val(touchedAt, t.TOUCHED_AT),
            element -> val(stringOf(inside(element.node(), "name")), t.NAME_REF),
            element -> val(stringOf(inside(element.node(), "collate")), t.COLLATE),
            element -> val(GraphitronAstEntries.tokenOf(inside(element.node(), "direction")),
                t.DIRECTION)));
        BindBatch.execute(dsl, rows, markers ->
            dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN,
                    t.TOUCHED_AT, t.NAME_REF, t.COLLATE, t.DIRECTION)
                .values(markers)
                .onDuplicateKeyUpdate()
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
                .set(t.NAME_REF, excluded(t.NAME_REF))
                .set(t.COLLATE, excluded(t.COLLATE))
                .set(t.DIRECTION, excluded(t.DIRECTION)));
    }
}
