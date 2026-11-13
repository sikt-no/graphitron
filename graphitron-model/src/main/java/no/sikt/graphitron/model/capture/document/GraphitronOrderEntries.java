package no.sikt.graphitron.model.capture.document;

import graphql.language.Directive;
import graphql.language.NullValue;
import graphql.language.ObjectValue;
import no.sikt.graphitron.model.sink.BindBatch;
import org.jooq.DSLContext;
import org.jooq.Rows;
import org.jooq.Table;

import java.time.LocalDateTime;
import java.util.List;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_ORDER_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_ORDER_FIELD_ENTRY;
import static no.sikt.graphitron.model.capture.document.GraphitronAstEntries.bool;
import static no.sikt.graphitron.model.capture.document.GraphitronAstEntries.inside;
import static no.sikt.graphitron.model.capture.document.GraphitronAstEntries.listedIn;
import static no.sikt.graphitron.model.capture.document.GraphitronAstEntries.string;
import static no.sikt.graphitron.model.capture.document.GraphitronAstEntries.stringOf;
import static no.sikt.graphitron.model.capture.document.GraphitronAstEntries.token;
import static no.sikt.graphitron.model.capture.document.GraphitronAstEntries.tokenOf;
import static org.jooq.impl.DSL.excluded;
import static org.jooq.impl.DSL.val;

/**
 * The ordering decode: what an {@code @order} on an enum value and a {@code @defaultOrder} on a field
 * say, into one pair of relations. The two directives write the same vocabulary, the three sorting
 * surfaces and the field list, and differ only in where they are written and in the application-level
 * direction {@code @defaultOrder} alone admits; which of them a row decodes is the application's name,
 * one join from its key, so one payload is stored once.
 *
 * <p>Keyed by the application's position, {@code graphql_ast_directive_application_entry}'s key, and
 * an element by its own value's position, with its application and index beside it.
 *
 * @see GraphitronAstEntries for what every site's decode holds in common
 */
final class GraphitronOrderEntries {

    private GraphitronOrderEntries() {}

    /**
     * Makes {@code source}'s ordering rows under {@code graph} be what {@code applications}, the
     * {@code @order} and {@code @defaultOrder} applications of the document, now say.
     */
    static void write(DSLContext dsl, String graph, String source, List<Directive> applications,
                      LocalDateTime touchedAt) {
        orders(dsl, graph, touchedAt, applications);
        orderFields(dsl, graph, source, touchedAt, applications);
        GraphitronAstEntries.sweep(dsl, graph, source, touchedAt, TABLES_TO_SWEEP);
    }

    /** What the sweep deletes from, elements first, on the terms {@link GraphitronAstEntries#sweep} states. */
    private static final List<Table<?>> TABLES_TO_SWEEP = List.of(
        GRAPHITRON_AST_ORDER_FIELD_ENTRY, GRAPHITRON_AST_ORDER_ENTRY);

    /**
     * A row for every application and not only for those that wrote an argument, because the field
     * list is the child relation: an application that took that surface has its own columns null and
     * still has to be here for its elements to hang off.
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
            application -> val(bool(application, "primaryKey"), t.PRIMARY_KEY),
            application -> val(token(application, "direction"), t.DIRECTION),
            application -> val(surface(application), t.SURFACE),
            application -> val(surfacesWritten(application), t.SURFACES_WRITTEN)));
        BindBatch.execute(dsl, rows, markers ->
            dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN,
                    t.TOUCHED_AT, t.INDEX_REF, t.PRIMARY_KEY, t.DIRECTION, t.SURFACE, t.SURFACES_WRITTEN)
                .values(markers)
                .onDuplicateKeyUpdate()
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
                .set(t.INDEX_REF, excluded(t.INDEX_REF))
                .set(t.PRIMARY_KEY, excluded(t.PRIMARY_KEY))
                .set(t.DIRECTION, excluded(t.DIRECTION))
                .set(t.SURFACE, excluded(t.SURFACE))
                .set(t.SURFACES_WRITTEN, excluded(t.SURFACES_WRITTEN)));
    }

    /**
     * The surface the application sorts by where it writes exactly one of an index, the primary key
     * and a field list; null where it writes none or several, which says nothing to sort by and
     * which the decode does not settle by precedence.
     */
    private static String surface(Directive application) {
        if (surfacesWritten(application) != 1) {
            return null;
        }
        if (string(application, "index") != null) {
            return "INDEX";
        }
        return Boolean.TRUE.equals(bool(application, "primaryKey")) ? "PRIMARY_KEY" : "FIELDS";
    }

    /** How many of the three surfaces the application writes; the directive admits exactly one. */
    private static int surfacesWritten(Directive application) {
        return (string(application, "index") != null ? 1 : 0)
            + (Boolean.TRUE.equals(bool(application, "primaryKey")) ? 1 : 0)
            + (fieldsWritten(application) ? 1 : 0);
    }

    private static boolean fieldsWritten(Directive application) {
        var fields = application.getArgument("fields");
        return fields != null && !(fields.getValue() instanceof NullValue);
    }

    /**
     * One row per object element of an application's field list, at its index. An element moved to
     * another index of the same application would meet the unique key on the index first, so the
     * displaced rows go before the write, as {@link GraphitronAstEntries#clearDisplaced} states.
     */
    private static void orderFields(DSLContext dsl, String graph, String source, LocalDateTime touchedAt,
                                    List<Directive> applications) {
        var t = GRAPHITRON_AST_ORDER_FIELD_ENTRY;
        var elements = listedIn(applications, "fields").stream()
            .filter(listed -> listed.node() instanceof ObjectValue)
            .toList();
        GraphitronAstEntries.clearDisplaced(dsl, t, graph, source, elements);
        var rows = elements.stream().collect(Rows.toRowList(
            element -> val(graph, t.GRAPH_NAME),
            element -> GraphQLAstEntries.sourceName(element.node()),
            element -> GraphQLAstEntries.sourceLine(element.node()),
            element -> GraphQLAstEntries.sourceColumn(element.node()),
            element -> val(touchedAt, t.TOUCHED_AT),
            element -> GraphQLAstEntries.sourceLine(element.application()),
            element -> GraphQLAstEntries.sourceColumn(element.application()),
            element -> val(element.position(), t.POSITION),
            element -> val(stringOf(inside(object(element), "name")), t.NAME_REF),
            element -> val(stringOf(inside(object(element), "collate")), t.COLLATE),
            element -> val(tokenOf(inside(object(element), "direction")), t.DIRECTION),
            element -> val(shape(object(element)), t.SHAPE)));
        BindBatch.execute(dsl, rows, markers ->
            dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN,
                    t.TOUCHED_AT, t.APPLICATION_LINE, t.APPLICATION_COLUMN, t.POSITION, t.NAME_REF,
                    t.COLLATE, t.DIRECTION, t.SHAPE)
                .values(markers)
                .onConflict(t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN)
                .doUpdate()
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
                .set(t.APPLICATION_LINE, excluded(t.APPLICATION_LINE))
                .set(t.APPLICATION_COLUMN, excluded(t.APPLICATION_COLUMN))
                .set(t.POSITION, excluded(t.POSITION))
                .set(t.NAME_REF, excluded(t.NAME_REF))
                .set(t.COLLATE, excluded(t.COLLATE))
                .set(t.DIRECTION, excluded(t.DIRECTION))
                .set(t.SHAPE, excluded(t.SHAPE)));
    }

    private static ObjectValue object(GraphitronAstEntries.Listed element) {
        return (ObjectValue) element.node();
    }

    /**
     * Which key the element names: a name alone a column, with a path a column read through it, a
     * method alone a method; anything else names no one key.
     */
    private static String shape(ObjectValue element) {
        boolean name = written(element, "name");
        boolean method = written(element, "method");
        boolean path = written(element, "path");
        if (name && !method) {
            return path ? "PATH" : "COLUMN";
        }
        return method && !name && !path ? "METHOD" : "MALFORMED";
    }

    /** Whether the element writes the field, an explicit null being no value written. */
    private static boolean written(ObjectValue element, String field) {
        var value = inside(element, field);
        return value != null && !(value instanceof NullValue);
    }
}
