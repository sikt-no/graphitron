package no.sikt.graphitron.model.capture.document;

import graphql.language.Directive;
import no.sikt.graphitron.model.grammar.QualifiedNameGrammar;
import org.jooq.DSLContext;
import org.jooq.Rows;
import org.jooq.Table;

import java.time.LocalDateTime;
import java.util.List;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_ENUM_ENTRY;
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
import static no.sikt.graphitron.model.capture.document.GraphitronEntries.ofKind;
import static no.sikt.graphitron.model.capture.document.GraphitronEntries.string;
import static no.sikt.graphitron.model.capture.document.GraphitronEntries.stringOf;
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
 * {@code @record} and {@code @error}, and two of those five surprise. {@code @error} has no
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
        tables(dsl, graph, touchedAt, applied(applications, "table"));
        scalarTypes(dsl, graph, touchedAt, applied(applications, "scalarType"));
        enums(dsl, graph, touchedAt, applied(applications, "enum"));
        records(dsl, graph, touchedAt, applied(applications, "record"));
        var handlers = elementsOf(applied(applications, "error"), "handlers");
        genericHandlers(dsl, graph, touchedAt, ofKind(handlers, "GENERIC"));
        databaseHandlers(dsl, graph, touchedAt, ofKind(handlers, "DATABASE"));
        validationHandlers(dsl, graph, touchedAt, ofKind(handlers, "VALIDATION"));
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
        GRAPHITRON_AST_ERROR_VALIDATION_HANDLER_ENTRY);

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
        if (rows.isEmpty()) {
            return;
        }
        dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN,
                t.TOUCHED_AT, t.TABLE_REF, t.TABLE_REF_NAMESPACE_PART, t.TABLE_REF_NAME_PART)
            .valuesOfRows(rows)
            .onDuplicateKeyUpdate()
            .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
            .set(t.TABLE_REF, excluded(t.TABLE_REF))
            .set(t.TABLE_REF_NAMESPACE_PART, excluded(t.TABLE_REF_NAMESPACE_PART))
            .set(t.TABLE_REF_NAME_PART, excluded(t.TABLE_REF_NAME_PART))
            .execute();
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
        if (rows.isEmpty()) {
            return;
        }
        dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN,
                t.TOUCHED_AT, t.SCALAR_REF, t.SCALAR_REF_CLASS_PART, t.SCALAR_REF_FIELD_PART)
            .valuesOfRows(rows)
            .onDuplicateKeyUpdate()
            .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
            .set(t.SCALAR_REF, excluded(t.SCALAR_REF))
            .set(t.SCALAR_REF_CLASS_PART, excluded(t.SCALAR_REF_CLASS_PART))
            .set(t.SCALAR_REF_FIELD_PART, excluded(t.SCALAR_REF_FIELD_PART))
            .execute();
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
        if (rows.isEmpty()) {
            return;
        }
        dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN,
                t.TOUCHED_AT, t.CLASS_NAME, t.METHOD, t.ARGMAPPING)
            .valuesOfRows(rows)
            .onDuplicateKeyUpdate()
            .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
            .set(t.CLASS_NAME, excluded(t.CLASS_NAME))
            .set(t.METHOD, excluded(t.METHOD))
            .set(t.ARGMAPPING, excluded(t.ARGMAPPING))
            .execute();
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
        if (rows.isEmpty()) {
            return;
        }
        dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN,
                t.TOUCHED_AT, t.CLASS_NAME)
            .valuesOfRows(rows)
            .onDuplicateKeyUpdate()
            .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
            .set(t.CLASS_NAME, excluded(t.CLASS_NAME))
            .execute();
    }

    private static void genericHandlers(DSLContext dsl, String graph, LocalDateTime touchedAt,
                                        List<GraphitronEntries.Element> handlers) {
        var t = GRAPHITRON_AST_ERROR_GENERIC_HANDLER_ENTRY;
        var rows = handlers.stream().collect(Rows.toRowList(
            handler -> val(graph, t.GRAPH_NAME),
            handler -> SdlEntries.sourceName(handler.application()),
            handler -> SdlEntries.sourceLine(handler.application()),
            handler -> SdlEntries.sourceColumn(handler.application()),
            handler -> val(handler.position(), t.POSITION),
            handler -> val(touchedAt, t.TOUCHED_AT),
            handler -> val(stringOf(inside(handler.value(), "className")), t.CLASS_NAME),
            handler -> val(stringOf(inside(handler.value(), "matches")), t.MATCHES),
            handler -> val(stringOf(inside(handler.value(), "description")), t.DESCRIPTION)));
        if (rows.isEmpty()) {
            return;
        }
        dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN, t.POSITION,
                t.TOUCHED_AT, t.CLASS_NAME, t.MATCHES, t.DESCRIPTION)
            .valuesOfRows(rows)
            .onDuplicateKeyUpdate()
            .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
            .set(t.CLASS_NAME, excluded(t.CLASS_NAME))
            .set(t.MATCHES, excluded(t.MATCHES))
            .set(t.DESCRIPTION, excluded(t.DESCRIPTION))
            .execute();
    }

    private static void databaseHandlers(DSLContext dsl, String graph, LocalDateTime touchedAt,
                                         List<GraphitronEntries.Element> handlers) {
        var t = GRAPHITRON_AST_ERROR_DATABASE_HANDLER_ENTRY;
        var rows = handlers.stream().collect(Rows.toRowList(
            handler -> val(graph, t.GRAPH_NAME),
            handler -> SdlEntries.sourceName(handler.application()),
            handler -> SdlEntries.sourceLine(handler.application()),
            handler -> SdlEntries.sourceColumn(handler.application()),
            handler -> val(handler.position(), t.POSITION),
            handler -> val(touchedAt, t.TOUCHED_AT),
            handler -> val(stringOf(inside(handler.value(), "code")), t.CODE),
            handler -> val(stringOf(inside(handler.value(), "sqlState")), t.SQL_STATE),
            handler -> val(stringOf(inside(handler.value(), "matches")), t.MATCHES),
            handler -> val(stringOf(inside(handler.value(), "description")), t.DESCRIPTION)));
        if (rows.isEmpty()) {
            return;
        }
        dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN, t.POSITION,
                t.TOUCHED_AT, t.CODE, t.SQL_STATE, t.MATCHES, t.DESCRIPTION)
            .valuesOfRows(rows)
            .onDuplicateKeyUpdate()
            .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
            .set(t.CODE, excluded(t.CODE))
            .set(t.SQL_STATE, excluded(t.SQL_STATE))
            .set(t.MATCHES, excluded(t.MATCHES))
            .set(t.DESCRIPTION, excluded(t.DESCRIPTION))
            .execute();
    }

    /** The position is the whole row: the directive gives this kind no field it may carry. */
    private static void validationHandlers(DSLContext dsl, String graph, LocalDateTime touchedAt,
                                           List<GraphitronEntries.Element> handlers) {
        var t = GRAPHITRON_AST_ERROR_VALIDATION_HANDLER_ENTRY;
        var rows = handlers.stream().collect(Rows.toRowList(
            handler -> val(graph, t.GRAPH_NAME),
            handler -> SdlEntries.sourceName(handler.application()),
            handler -> SdlEntries.sourceLine(handler.application()),
            handler -> SdlEntries.sourceColumn(handler.application()),
            handler -> val(handler.position(), t.POSITION),
            handler -> val(touchedAt, t.TOUCHED_AT)));
        if (rows.isEmpty()) {
            return;
        }
        dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN, t.POSITION,
                t.TOUCHED_AT)
            .valuesOfRows(rows)
            .onDuplicateKeyUpdate()
            .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
            .execute();
    }
}
