package no.sikt.graphitron.model.capture.document;

import graphql.language.ArrayValue;
import graphql.language.Directive;
import graphql.language.EnumValue;
import graphql.language.ObjectField;
import graphql.language.ObjectValue;
import graphql.language.StringValue;
import graphql.language.Value;
import graphql.schema.idl.TypeDefinitionRegistry;
import no.sikt.graphitron.model.grammar.ConstantReferenceGrammar;
import no.sikt.graphitron.model.grammar.QualifiedNameGrammar;
import org.jooq.DSLContext;
import org.jooq.Rows;
import org.jooq.Table;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static graphql.language.AstPrinter.printAstCompact;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_ENUM_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_ERROR_DATABASE_HANDLER_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_ERROR_GENERIC_HANDLER_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_ERROR_VALIDATION_HANDLER_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_RECORD_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_SCALAR_TYPE_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_TABLE_ENTRY;
import static org.jooq.impl.DSL.excluded;
import static org.jooq.impl.DSL.val;

/**
 * Writes what a graphitron directive application meant, beside the row saying it was applied.
 *
 * <p>Keyed by the application's own position, which is {@code graphql_ast_type_directive_entry}'s
 * key, so a row here is the decode of exactly one row there. Which declaration the directive was
 * written on, and in which file, is one join away rather than a column.
 *
 * <p>Nothing here resolves, and nothing is quarantined. A written name is cut where its grammar
 * cuts it and kept as typed otherwise; a value of some other shape decodes to null, every argument
 * of every application already standing verbatim in {@code graphql_ast_applied_argument_entry}.
 * Whether the table exists, whether the class is on the classpath and which of two documents the
 * corpus honours are questions for the anchors.
 *
 * <p>The type-site directives are {@code @table}, {@code @scalarType}, {@code @enum},
 * {@code @record} and {@code @error}, and two of those five surprise. {@code @error} has no
 * relation, its only argument being the handler list, so a row carrying its key and nothing else
 * would say what the applied-directive row already says. Its handlers have one relation per kind,
 * because the kind decides which of the input's six fields mean anything: GENERIC matches by class
 * identity, DATABASE by one of two SQL discriminators, and VALIDATION carries nothing at all.
 */
public final class GraphitronEntries {

    private GraphitronEntries() {}

    /**
     * Makes {@code source}'s decoded rows under {@code graph} be what {@code document}'s type-site
     * applications now say.
     *
     * <p>Runs after the AST entries of the same reading, the rows here hanging off theirs by key.
     */
    public static void write(DSLContext dsl, String graph, String source,
                             TypeDefinitionRegistry document, LocalDateTime touchedAt) {
        var applications = SdlEntries.directivesOnTypes(document);
        tables(dsl, graph, touchedAt, applied(applications, "table"));
        scalarTypes(dsl, graph, touchedAt, applied(applications, "scalarType"));
        enums(dsl, graph, touchedAt, applied(applications, "enum"));
        records(dsl, graph, touchedAt, applied(applications, "record"));
        var handlers = handlers(applied(applications, "error"));
        genericHandlers(dsl, graph, touchedAt, ofKind(handlers, "GENERIC"));
        databaseHandlers(dsl, graph, touchedAt, ofKind(handlers, "DATABASE"));
        validationHandlers(dsl, graph, touchedAt, ofKind(handlers, "VALIDATION"));
        sweep(dsl, graph, source, touchedAt);
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

    private static void sweep(DSLContext dsl, String graph, String source, LocalDateTime touchedAt) {
        // Table.field(Field) is a lookup by name returning the loop's own typed column, so one
        // relation's three name them on all seven.
        var named = GRAPHITRON_AST_TABLE_ENTRY;
        for (Table<?> table : TABLES_TO_SWEEP) {
            dsl.deleteFrom(table)
                .where(table.field(named.GRAPH_NAME).eq(graph))
                .and(table.field(named.SOURCE_NAME).eq(source))
                .and(table.field(named.TOUCHED_AT).ne(touchedAt))
                .execute();
        }
    }

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

    /** One handler of one application, at the index it was written at inside the list. */
    private record Handler(Directive application, int position, ObjectValue value) {}

    private static void genericHandlers(DSLContext dsl, String graph, LocalDateTime touchedAt,
                                        List<Handler> handlers) {
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
                                         List<Handler> handlers) {
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
                                           List<Handler> handlers) {
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

    /**
     * The handlers of one kind. A handler whose token is none of the three the directive declares
     * lands in no relation, its list position spoken for and its text standing in the verbatim
     * argument row.
     */
    private static List<Handler> ofKind(List<Handler> handlers, String kind) {
        return handlers.stream()
            .filter(handler -> kind.equals(tokenOf(inside(handler.value(), "handler")))).toList();
    }

    /**
     * Every handler of every application, each with the index it was written at. An element that is
     * not an object literal takes its index and contributes no row, so the indices of the ones after
     * it are the ones the author would count.
     */
    private static List<Handler> handlers(List<Directive> applications) {
        var handlers = new ArrayList<Handler>();
        for (Directive application : applications) {
            int position = 0;
            for (Object written : elements(application, "handlers")) {
                if (written instanceof ObjectValue object) {
                    handlers.add(new Handler(application, position, object));
                }
                position++;
            }
        }
        return handlers;
    }

    // ------------------------------------------------------------------- reading an argument

    private static List<Directive> applied(List<SdlEntries.Nested<Directive>> applications,
                                           String name) {
        return applications.stream().map(SdlEntries.Nested::node)
            .filter(application -> application.getName().equals(name)).toList();
    }

    /** The argument's value, or null where the author wrote no such argument. */
    private static Value<?> argument(Directive application, String name) {
        var written = application.getArgument(name);
        return written == null ? null : written.getValue();
    }

    private static String string(Directive application, String name) {
        return stringOf(argument(application, name));
    }

    /** A field of the object literal an argument holds, or null where either is absent. */
    private static String inside(Directive application, String argumentName, String fieldName) {
        return argument(application, argumentName) instanceof ObjectValue object
            ? stringOf(inside(object, fieldName)) : null;
    }

    private static Value<?> inside(ObjectValue object, String fieldName) {
        for (ObjectField written : object.getObjectFields()) {
            if (written.getName().equals(fieldName)) {
                return written.getValue();
            }
        }
        return null;
    }

    /**
     * The elements of an argument written as a list, or none where it was written as anything else.
     * Wildcarded because graphql-java hands them back raw and nothing here needs the element type.
     */
    private static List<?> elements(Directive application, String name) {
        return argument(application, name) instanceof ArrayValue array ? array.getValues() : List.of();
    }

    /** A written string, or null where the value is any other shape. */
    private static String stringOf(Value<?> value) {
        return value instanceof StringValue written ? written.getValue() : null;
    }

    /** A written enum token or string, either being how an author spells one of a fixed set. */
    private static String tokenOf(Value<?> value) {
        return switch (value) {
            case null -> null;
            case EnumValue token -> token.getName();
            case StringValue written -> written.getValue();
            default -> printAstCompact(value);
        };
    }

    private static String classPart(String written) {
        return ConstantReferenceGrammar.split(written)
            instanceof ConstantReferenceGrammar.Reference.Parsed parsed ? parsed.classFqn() : null;
    }

    private static String fieldPart(String written) {
        return ConstantReferenceGrammar.split(written)
            instanceof ConstantReferenceGrammar.Reference.Parsed parsed ? parsed.fieldName() : null;
    }
}
