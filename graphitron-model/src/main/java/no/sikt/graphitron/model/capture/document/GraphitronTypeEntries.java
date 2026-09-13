package no.sikt.graphitron.model.capture.document;

import graphql.language.Directive;
import no.sikt.graphitron.model.grammar.QualifiedNameGrammar;
import no.sikt.graphitron.model.sink.BindBatch;
import org.jooq.DSLContext;
import org.jooq.Rows;
import org.jooq.Table;

import java.time.LocalDateTime;
import java.util.List;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_ENUM_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_NODE_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_NODE_KEYCOLUMN_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_ERROR_DATABASE_HANDLER_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_ERROR_GENERIC_HANDLER_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_ERROR_VALIDATION_HANDLER_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_RECORD_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_SCALAR_TYPE_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_TABLE_ENTRY;
import static no.sikt.graphitron.model.capture.document.GraphitronEntries.applied;
import static no.sikt.graphitron.model.capture.document.GraphitronEntries.classPart;
import static no.sikt.graphitron.model.capture.document.GraphitronEntries.elementsOf;
import static no.sikt.graphitron.model.capture.document.GraphitronEntries.fieldPart;
import static no.sikt.graphitron.model.capture.document.GraphitronEntries.inside;
import static no.sikt.graphitron.model.capture.document.GraphitronEntries.naming;
import static no.sikt.graphitron.model.capture.document.GraphitronEntries.ofKind;
import static no.sikt.graphitron.model.capture.document.GraphitronEntries.string;
import static no.sikt.graphitron.model.capture.document.GraphitronEntries.writtenIn;
import static no.sikt.graphitron.model.capture.document.GraphitronEntries.stringOf;
import static no.sikt.graphitron.model.capture.document.GraphitronEntries.wrote;
import static org.jooq.impl.DSL.excluded;
import static org.jooq.impl.DSL.val;

/**
 * The type site's half of the decode: what a directive written on a type declaration meant.
 *
 * <p>Keyed by the application's own position, which is {@code graphql_ast_type_directive_entry}'s
 * key, so a row here is the decode of exactly one row there. Which declaration the directive was
 * written on, and in which file, is one join away rather than a column.
 *
 * <p>The type-site directives this writes are {@code @table}, {@code @scalarType}, {@code @enum},
 * {@code @record}, {@code @error} and {@code @node}, and two of those six surprise. {@code @error} has no
 * relation, its only argument being the handler list, so a row carrying its key and nothing else
 * would say what the applied-directive row already says. Its handlers have one relation per kind,
 * because the kind decides which of the input's six fields mean anything: GENERIC matches by class
 * identity, DATABASE by one of two SQL discriminators, and VALIDATION carries nothing at all.
 *
 * @see GraphitronEntries for what every site's decode holds in common
 */
final class GraphitronTypeEntries {

    private GraphitronTypeEntries() {}

    /**
     * Makes {@code source}'s type-site rows under {@code graph} be what {@code document}'s
     * applications now say.
     */
    static void write(DSLContext dsl, String graph, String source,
                      List<SdlEntries.Nested<Directive>> applications, LocalDateTime touchedAt) {
        tables(dsl, graph, touchedAt, wrote(applied(applications, "table"), "name"));
        scalarTypes(dsl, graph, touchedAt, wrote(applied(applications, "scalarType"), "scalar"));
        enums(dsl, graph, touchedAt, naming(applied(applications, "enum"), "enumReference"));
        records(dsl, graph, touchedAt, naming(applied(applications, "record"), "record"));
        var handlers = elementsOf(applied(applications, "error"), "handlers");
        genericHandlers(dsl, graph, touchedAt, ofKind(handlers, "GENERIC").stream()
            .filter(handler -> stringOf(inside(handler.node(), "className")) != null).toList());
        databaseHandlers(dsl, graph, touchedAt, ofKind(handlers, "DATABASE"));
        validationHandlers(dsl, graph, touchedAt, ofKind(handlers, "VALIDATION"));

        var nodes = applied(applications, "node");
        nodes(dsl, graph, touchedAt, nodes);
        nodeKeyColumns(dsl, graph, touchedAt, nodes);
        GraphitronEntries.sweep(dsl, graph, source, touchedAt, TABLES_TO_SWEEP);
    }

    /**
     * What the sweep deletes from, and the only thing that reads it. Listed rather than found by
     * prefix: a relation added above and not here would keep its stale rows silently.
     *
     * <p>In no particular order, these relations referencing one another not at all. An application
     * the author moved or deleted is swept by the cascade from the directive row it hung on; what
     * is left for this sweep is the application whose position another directive now occupies.
     */
    private static final List<Table<?>> TABLES_TO_SWEEP = List.of(
        GRAPHITRON_AST_TABLE_ENTRY, GRAPHITRON_AST_SCALAR_TYPE_ENTRY, GRAPHITRON_AST_ENUM_ENTRY,
        GRAPHITRON_AST_RECORD_ENTRY, GRAPHITRON_AST_ERROR_GENERIC_HANDLER_ENTRY,
        GRAPHITRON_AST_ERROR_DATABASE_HANDLER_ENTRY,
        GRAPHITRON_AST_ERROR_VALIDATION_HANDLER_ENTRY,
        GRAPHITRON_AST_NODE_KEYCOLUMN_ENTRY, GRAPHITRON_AST_NODE_ENTRY);

    private static void tables(DSLContext dsl, String graph, LocalDateTime touchedAt,
                               List<Directive> applications) {
        var t = GRAPHITRON_AST_TABLE_ENTRY;
        var rows = applications.stream().collect(Rows.toRowList(
            application -> val(graph, t.GRAPH_NAME),
            application -> SdlEntries.sourceName(application),
            application -> SdlEntries.sourceLine(application),
            application -> SdlEntries.sourceColumn(application),
            application -> val(touchedAt, t.TOUCHED_AT),
            application -> val(string(application, "name"), t.TABLE_REF),
            application -> val(QualifiedNameGrammar.namespacePart(string(application, "name")),
                t.TABLE_REF_NAMESPACE_PART),
            application -> val(QualifiedNameGrammar.namePart(string(application, "name")),
                t.TABLE_REF_NAME_PART)));
        BindBatch.execute(dsl, rows, markers ->
            dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN,
                    t.TOUCHED_AT, t.TABLE_REF, t.TABLE_REF_NAMESPACE_PART, t.TABLE_REF_NAME_PART)
                .values(markers)
                .onDuplicateKeyUpdate()
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
                .set(t.TABLE_REF, excluded(t.TABLE_REF))
                .set(t.TABLE_REF_NAMESPACE_PART, excluded(t.TABLE_REF_NAMESPACE_PART))
                .set(t.TABLE_REF_NAME_PART, excluded(t.TABLE_REF_NAME_PART)));
    }

    private static void scalarTypes(DSLContext dsl, String graph, LocalDateTime touchedAt,
                                    List<Directive> applications) {
        var t = GRAPHITRON_AST_SCALAR_TYPE_ENTRY;
        var rows = applications.stream().collect(Rows.toRowList(
            application -> val(graph, t.GRAPH_NAME),
            application -> SdlEntries.sourceName(application),
            application -> SdlEntries.sourceLine(application),
            application -> SdlEntries.sourceColumn(application),
            application -> val(touchedAt, t.TOUCHED_AT),
            application -> val(string(application, "scalar"), t.SCALAR_REF),
            application -> val(classPart(string(application, "scalar")), t.SCALAR_REF_CLASS_PART),
            application -> val(fieldPart(string(application, "scalar")), t.SCALAR_REF_FIELD_PART)));
        BindBatch.execute(dsl, rows, markers ->
            dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN,
                    t.TOUCHED_AT, t.SCALAR_REF, t.SCALAR_REF_CLASS_PART, t.SCALAR_REF_FIELD_PART)
                .values(markers)
                .onDuplicateKeyUpdate()
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
                .set(t.SCALAR_REF, excluded(t.SCALAR_REF))
                .set(t.SCALAR_REF_CLASS_PART, excluded(t.SCALAR_REF_CLASS_PART))
                .set(t.SCALAR_REF_FIELD_PART, excluded(t.SCALAR_REF_FIELD_PART)));
    }

    private static void enums(DSLContext dsl, String graph, LocalDateTime touchedAt,
                              List<Directive> applications) {
        var t = GRAPHITRON_AST_ENUM_ENTRY;
        var rows = applications.stream().collect(Rows.toRowList(
            application -> val(graph, t.GRAPH_NAME),
            application -> SdlEntries.sourceName(application),
            application -> SdlEntries.sourceLine(application),
            application -> SdlEntries.sourceColumn(application),
            application -> val(touchedAt, t.TOUCHED_AT),
            application -> val(inside(application, "enumReference", "className"), t.CLASS_NAME),
            application -> val(inside(application, "enumReference", "method"), t.METHOD),
            application -> val(inside(application, "enumReference", "argMapping"), t.ARGMAPPING)));
        BindBatch.execute(dsl, rows, markers ->
            dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN,
                    t.TOUCHED_AT, t.CLASS_NAME, t.METHOD, t.ARGMAPPING)
                .values(markers)
                .onDuplicateKeyUpdate()
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
                .set(t.CLASS_NAME, excluded(t.CLASS_NAME))
                .set(t.METHOD, excluded(t.METHOD))
                .set(t.ARGMAPPING, excluded(t.ARGMAPPING)));
    }

    private static void records(DSLContext dsl, String graph, LocalDateTime touchedAt,
                                List<Directive> applications) {
        var t = GRAPHITRON_AST_RECORD_ENTRY;
        var rows = applications.stream().collect(Rows.toRowList(
            application -> val(graph, t.GRAPH_NAME),
            application -> SdlEntries.sourceName(application),
            application -> SdlEntries.sourceLine(application),
            application -> SdlEntries.sourceColumn(application),
            application -> val(touchedAt, t.TOUCHED_AT),
            application -> val(inside(application, "record", "className"), t.CLASS_NAME)));
        BindBatch.execute(dsl, rows, markers ->
            dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN,
                    t.TOUCHED_AT, t.CLASS_NAME)
                .values(markers)
                .onDuplicateKeyUpdate()
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
                .set(t.CLASS_NAME, excluded(t.CLASS_NAME)));
    }

    private static void genericHandlers(DSLContext dsl, String graph, LocalDateTime touchedAt,
                                        List<GraphitronEntries.Element> handlers) {
        var t = GRAPHITRON_AST_ERROR_GENERIC_HANDLER_ENTRY;
        var rows = handlers.stream().collect(Rows.toRowList(
            handler -> val(graph, t.GRAPH_NAME),
            handler -> SdlEntries.sourceName(handler.node()),
            handler -> SdlEntries.sourceLine(handler.node()),
            handler -> SdlEntries.sourceColumn(handler.node()),
            handler -> val(touchedAt, t.TOUCHED_AT),
            handler -> val(stringOf(inside(handler.node(), "className")), t.CLASS_NAME),
            handler -> val(stringOf(inside(handler.node(), "matches")), t.MATCHES),
            handler -> val(stringOf(inside(handler.node(), "description")), t.DESCRIPTION)));
        BindBatch.execute(dsl, rows, markers ->
            dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN,
                    t.TOUCHED_AT, t.CLASS_NAME, t.MATCHES, t.DESCRIPTION)
                .values(markers)
                .onDuplicateKeyUpdate()
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
                .set(t.CLASS_NAME, excluded(t.CLASS_NAME))
                .set(t.MATCHES, excluded(t.MATCHES))
                .set(t.DESCRIPTION, excluded(t.DESCRIPTION)));
    }

    private static void databaseHandlers(DSLContext dsl, String graph, LocalDateTime touchedAt,
                                         List<GraphitronEntries.Element> handlers) {
        var t = GRAPHITRON_AST_ERROR_DATABASE_HANDLER_ENTRY;
        var rows = handlers.stream().collect(Rows.toRowList(
            handler -> val(graph, t.GRAPH_NAME),
            handler -> SdlEntries.sourceName(handler.node()),
            handler -> SdlEntries.sourceLine(handler.node()),
            handler -> SdlEntries.sourceColumn(handler.node()),
            handler -> val(touchedAt, t.TOUCHED_AT),
            handler -> val(stringOf(inside(handler.node(), "code")), t.CODE),
            handler -> val(stringOf(inside(handler.node(), "sqlState")), t.SQL_STATE),
            handler -> val(stringOf(inside(handler.node(), "matches")), t.MATCHES),
            handler -> val(stringOf(inside(handler.node(), "description")), t.DESCRIPTION)));
        BindBatch.execute(dsl, rows, markers ->
            dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN,
                    t.TOUCHED_AT, t.CODE, t.SQL_STATE, t.MATCHES, t.DESCRIPTION)
                .values(markers)
                .onDuplicateKeyUpdate()
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
                .set(t.CODE, excluded(t.CODE))
                .set(t.SQL_STATE, excluded(t.SQL_STATE))
                .set(t.MATCHES, excluded(t.MATCHES))
                .set(t.DESCRIPTION, excluded(t.DESCRIPTION)));
    }

    /** The position is the whole row: the directive gives this kind no field it may carry. */
    private static void validationHandlers(DSLContext dsl, String graph, LocalDateTime touchedAt,
                                           List<GraphitronEntries.Element> handlers) {
        var t = GRAPHITRON_AST_ERROR_VALIDATION_HANDLER_ENTRY;
        var rows = handlers.stream().collect(Rows.toRowList(
            handler -> val(graph, t.GRAPH_NAME),
            handler -> SdlEntries.sourceName(handler.node()),
            handler -> SdlEntries.sourceLine(handler.node()),
            handler -> SdlEntries.sourceColumn(handler.node()),
            handler -> val(touchedAt, t.TOUCHED_AT)));
        BindBatch.execute(dsl, rows, markers ->
            dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN,
                    t.TOUCHED_AT)
                .values(markers)
                .onDuplicateKeyUpdate()
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT)));
    }

    /**
     * A row for every application and not only for those that wrote an argument. Both arguments are
     * optional, an author writing a bare {@code @node} is asking for the type's own name and the
     * database's own key, and the key-column list is the child relation its elements hang off.
     */
    private static void nodes(DSLContext dsl, String graph, LocalDateTime touchedAt,
                              List<Directive> applications) {
        var t = GRAPHITRON_AST_NODE_ENTRY;
        var rows = applications.stream().collect(Rows.toRowList(
            application -> val(graph, t.GRAPH_NAME),
            application -> SdlEntries.sourceName(application),
            application -> SdlEntries.sourceLine(application),
            application -> SdlEntries.sourceColumn(application),
            application -> val(touchedAt, t.TOUCHED_AT),
            application -> val(string(application, "typeId"), t.TYPE_ID)));
        BindBatch.execute(dsl, rows, markers ->
            dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN,
                    t.TOUCHED_AT, t.TYPE_ID)
                .values(markers)
                .onDuplicateKeyUpdate()
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
                .set(t.TYPE_ID, excluded(t.TYPE_ID)));
    }

    /**
     * The key columns, which are a list of bare strings rather than of object literals, so they are
     * read through {@link GraphitronEntries#writtenIn}. Each row names the element it decodes. The
     * order is part of what the directive says, being the order the columns take inside an id, and
     * that is why it is not restated here: the element's own row carries the index already.
     */
    private static void nodeKeyColumns(DSLContext dsl, String graph, LocalDateTime touchedAt,
                                       List<Directive> applications) {
        var t = GRAPHITRON_AST_NODE_KEYCOLUMN_ENTRY;
        var rows = writtenIn(applications, "keyColumns").stream().collect(Rows.toRowList(
            column -> val(graph, t.GRAPH_NAME),
            column -> SdlEntries.sourceName(column.node()),
            column -> SdlEntries.sourceLine(column.node()),
            column -> SdlEntries.sourceColumn(column.node()),
            column -> val(touchedAt, t.TOUCHED_AT),
            column -> val(column.value(), t.COLUMN_REF)));
        BindBatch.execute(dsl, rows, markers ->
            dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN,
                    t.TOUCHED_AT, t.COLUMN_REF)
                .values(markers)
                .onDuplicateKeyUpdate()
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
                .set(t.COLUMN_REF, excluded(t.COLUMN_REF)));
    }
}
