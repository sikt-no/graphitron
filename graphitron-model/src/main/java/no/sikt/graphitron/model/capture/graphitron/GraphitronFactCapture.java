package no.sikt.graphitron.model.capture.graphitron;

import graphql.language.Argument;
import graphql.language.ArrayValue;
import graphql.language.AstPrinter;
import graphql.language.BooleanValue;
import graphql.language.Directive;
import graphql.language.EnumValue;
import graphql.language.IntValue;
import graphql.language.ObjectField;
import graphql.language.ObjectValue;
import graphql.language.SourceLocation;
import graphql.language.StringValue;
import graphql.language.Value;
import graphql.parser.InvalidSyntaxException;
import graphql.parser.Parser;
import no.sikt.graphitron.model.capture.macro.MacroCapture;
import no.sikt.graphitron.model.derive.FieldEndpoints;
import no.sikt.graphitron.model.derive.Nodes;
import no.sikt.graphitron.model.derive.NodeKeyColumns;
import no.sikt.graphitron.model.derive.TableTypes;
import no.sikt.graphitron.model.catalog.SchemaCoordinateSyntax;
import no.sikt.graphitron.model.capture.sdl.SdlFactCapture.SiteRef;
import no.sikt.graphitron.model.capture.sdl.SdlFactCapture;
import no.sikt.graphitron.model.grammar.ArgMappingSigil;
import no.sikt.graphitron.model.grammar.QualifiedNameGrammar;
import no.sikt.graphitron.model.selection.GraphQLSelectionParseException;
import no.sikt.graphitron.model.selection.GraphQLSelectionParser;
import no.sikt.graphitron.model.selection.ParsedEntry;
import no.sikt.graphitron.model.sink.FactSink;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Table;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_ARGMAPPING_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ARGUMENT_BINDING;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ARGUMENT_CONDITION;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ARGUMENT_CONDITION_CONTEXT_ARG;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ARGUMENT_LOOKUP_KEY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ARGUMENT_NODE_ID;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ARGUMENT_REFERENCE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ARGUMENT_REFERENCE_FOR;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ARGUMENT_REFERENCE_FOR_STEP;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ARGUMENT_REFERENCE_STEP;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_CONNECTION;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_DEFAULT_ORDER;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_DEFAULT_ORDER_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_DISCRIMINATE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_DISCRIMINATOR;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ENUM;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ENUM_VALUE_BINDING;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ERROR;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ERROR_HANDLER;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_EXTERNAL_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FACET;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FEDERATION_KEY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FEDERATION_KEY_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FEDERATION_KEY_FIELD_SEGMENT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_BINDING;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_CONDITION;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_CONDITION_CONTEXT_ARG;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_LOOKUP_KEY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_NAVIGATION;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_NODE_ID;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_REFERENCE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_REFERENCE_STEP;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_INDEX;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_LINK;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_LINK_IMPORT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_METHOD_REFERENCE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_MULTITABLE_REFERENCE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_MUTATION;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_NODE_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_NODE_KEYCOLUMN_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ORDER;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ORDER_BY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ORDER_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_PIVOT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_RECORD;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_REFERENCE_FOR;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_REFERENCE_FOR_STEP;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ROUTINE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ROUTINE_COLUMN_MAPPING_PAIR;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_SCALAR_TYPE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_SERVICE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_SERVICE_CONTEXT_ARG;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_SPELLED_REFERENCE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_SPLIT_QUERY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_TABLE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_TENANT_FAN_OUT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_UNDECODED_ARGUMENT;
import static no.sikt.graphitron.model.Tables.GRAPHQL_ARGUMENT_DIRECTIVE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_ARGUMENT_DIRECTIVE_ARG;
import static no.sikt.graphitron.model.Tables.GRAPHQL_ENUM_VALUE_DIRECTIVE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_ENUM_VALUE_DIRECTIVE_ARG;
import static no.sikt.graphitron.model.Tables.GRAPHQL_FIELD_DIRECTIVE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_FIELD_DIRECTIVE_ARG;
import static no.sikt.graphitron.model.Tables.GRAPHQL_SCHEMA_DIRECTIVE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_SCHEMA_DIRECTIVE_ARG;
import static no.sikt.graphitron.model.Tables.GRAPHQL_TYPE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_TYPE_DIRECTIVE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_TYPE_DIRECTIVE_ARG;
import static no.sikt.graphitron.model.Tables.INTENT_CONNECTION_ELEMENT_TYPE;
import static no.sikt.graphitron.model.Tables.INTENT_EXPANDED_FIELD;
import static org.jooq.impl.DSL.coalesce;
import static org.jooq.impl.DSL.val;
import static org.jooq.impl.DSL.when;

/**
 * The {@code graphitron_} family's writer: decodes the graphitron and federation directive inventory
 * into typed relations while {@link SdlFactCapture} walks.
 *
 * <p>A row here is still a transcription, not a conclusion. It restates what an application spelled,
 * in graphitron's vocabulary rather than the document's, so a reader gets typed columns instead of
 * rendered SDL literals and nothing more. What the generator will actually do with those readings is
 * a layer above this one, and the schema keeps the {@code intent_} prefix free for it.
 *
 * <p>What decodes here and what does not follows one rule: a decode happens at capture exactly
 * when it needs parse-boundary knowledge SQL cannot express (the graphql-java AST, federation's
 * field-set grammar, the shared {@code argMapping} pair grammar). Everything computable as a
 * query over captured columns is derivation and stays out, which is why type wrapping, structured
 * directive arguments, and field sets are columns and child relations here while name resolution
 * and effective-value defaulting are not.
 *
 * <p>The decode never rejects. A literal that does not fit its declared shape leaves its typed
 * column NULL and quarantines raw in {@code graphitron_undecoded_argument} with its location, so the
 * authored text survives and the malformed-literal detection has its row. Those paths stay
 * dormant while schema assembly still runs upstream and rejects such schemas first.
 *
 * <p>Only what the author wrote is stored: an omitted argument is a NULL column or an absent row,
 * never a default-filled one. Effective values are derivation views, and for the graphitron
 * namespace the defaults are generator constants rather than captured facts.
 */
public final class GraphitronFactCapture {

    /** Federation's two decoded applications; every other federation directive is fidelity only. */
    private static final String FEDERATION_KEY = "key";
    private static final String FEDERATION_LINK = "link";

    private final FactSink sink;

    GraphitronFactCapture(FactSink sink) {
        this.sink = sink;
    }


    // ---------------------------------------------------------------- the gatherer's own entry

    /**
     * Decodes every directive application the SDL gatherer transcribed, reading the applications
     * out of the store rather than being handed them while the walk still holds the parse.
     *
     * <p>That is what makes this a gatherer rather than a visitor the SDL walk calls. A decode
     * driven by callbacks can only ever see what the walk holds at that instant, which is one
     * directive and no store at all: it cannot join the coordinate it is decoding against anything,
     * and every fact it needs from elsewhere has to arrive as a parameter somebody threaded to the
     * walk. Driven from the store it runs after both crawlers have flushed, so the whole
     * {@code graphql_} transcription and the whole catalog are readable, which is what the
     * gatherer's declared dependencies say and what a decode with a default to resolve needs.
     *
     * <p>The store carries the applications losslessly. A coordinate, a name and an ordinal are
     * columns; the application's own position is three more; and each authored argument is a row
     * holding the literal as {@code AstPrinter} rendered it. So the input to a decode is rebuilt by
     * parsing each literal back to the {@link Value} it was printed from, which is the same
     * round-trip the applied-directive emitter already ships into generated sources. What the
     * decode reads has not changed at all: it asks a directive for an argument by name and for its
     * own source location, and both survive the round-trip exactly.
     */
    public static Map<String, Set<String>> capture(FactSink sink, DSLContext dsl, String graphName) {
        var decode = new GraphitronFactCapture(sink);
        decode.schemaDirectives(dsl, graphName);
        decode.typeDirectives(dsl, graphName);
        decode.fieldDirectives(dsl, graphName);
        decode.argumentDirectives(dsl, graphName);
        decode.enumValueDirectives(dsl, graphName);
        // The gatherer's own stages, each reading what the one before it wrote. A flush inside the
        // load's transaction is what makes that possible and publishes nothing: macro expansion is
        // driven by the graphitron_connection rows the decode above just produced, and the
        // navigation rule is stated over the population expansion completes.
        sink.flush();
        TableTypes.derive(dsl, graphName);
        Nodes.derive(dsl, graphName);
        NodeKeyColumns.derive(dsl, graphName);
        var edges = MacroCapture.expand(sink, dsl, graphName);
        sink.flush();
        navigation(dsl, graphName);
        // Last, because its target rule reads the navigation the line above writes and its
        // departure reads the bindings three lines up.
        FieldEndpoints.derive(dsl, graphName);
        return edges;
    }


    /**
     * Which type each field's own generated SQL navigates as, over the population the generator
     * emits rather than over the document: the connection's element where the field's named type is
     * a connection, and the named type itself otherwise.
     *
     * <p>Two rungs and not the three this rule used to carry. The retired one took the expression
     * the author wrote wherever a macro had rewritten it, which was the only rung needing something
     * no relation held, and therefore the only reason this rule had to be computed where the parse
     * was. It was also carrying nothing: measured over a consumer-size schema every row it answered
     * is the row the two rungs below it answer, a synthesised connection's {@code edges.node} being
     * the element the authored expression named. Stated as one statement rather than as a view
     * because a reader joins on what it projects and wants an index on that column.
     */
    private static void navigation(DSLContext dsl, String graphName) {
        dsl.insertInto(GRAPHITRON_FIELD_NAVIGATION)
            .columns(GRAPHITRON_FIELD_NAVIGATION.GRAPH_NAME, GRAPHITRON_FIELD_NAVIGATION.TYPE_NAME,
                GRAPHITRON_FIELD_NAVIGATION.FIELD_NAME, GRAPHITRON_FIELD_NAVIGATION.BASIS,
                GRAPHITRON_FIELD_NAVIGATION.NAVIGATED_TYPE_NAME)
            .select(dsl
                .select(INTENT_EXPANDED_FIELD.GRAPH_NAME, INTENT_EXPANDED_FIELD.TYPE_NAME,
                    INTENT_EXPANDED_FIELD.FIELD_NAME,
                    when(INTENT_CONNECTION_ELEMENT_TYPE.ELEMENT_TYPE_NAME.isNull(),
                        val("NAMED_TYPE")).otherwise(val("CONNECTION_ELEMENT")),
                    coalesce(INTENT_CONNECTION_ELEMENT_TYPE.ELEMENT_TYPE_NAME,
                        INTENT_EXPANDED_FIELD.NAMED_TYPE))
                .from(INTENT_EXPANDED_FIELD)
                .leftJoin(INTENT_CONNECTION_ELEMENT_TYPE)
                .on(INTENT_CONNECTION_ELEMENT_TYPE.GRAPH_NAME.eq(INTENT_EXPANDED_FIELD.GRAPH_NAME))
                .and(INTENT_CONNECTION_ELEMENT_TYPE.TYPE_NAME.eq(INTENT_EXPANDED_FIELD.NAMED_TYPE))
                .where(INTENT_EXPANDED_FIELD.GRAPH_NAME.eq(graphName)))
            .execute();
    }

    private void schemaDirectives(DSLContext dsl, String graphName) {
        var arguments = argumentsBy(dsl, graphName, GRAPHQL_SCHEMA_DIRECTIVE_ARG,
            "DIRECTIVE_NAME", "ORDINAL");
        for (var row : dsl.selectFrom(GRAPHQL_SCHEMA_DIRECTIVE)
                .where(GRAPHQL_SCHEMA_DIRECTIVE.GRAPH_NAME.eq(graphName))
                .orderBy(GRAPHQL_SCHEMA_DIRECTIVE.DIRECTIVE_NAME, GRAPHQL_SCHEMA_DIRECTIVE.ORDINAL)
                .fetch()) {
            captureSchemaDirective(
                directive(row.getDirectiveName(),
                    location(row.getSourceName(), row.getSourceLine(), row.getSourceColumn()),
                    arguments.get(List.of(row.getDirectiveName(), row.getOrdinal()))),
                row.getOrdinal());
        }
    }

    private void typeDirectives(DSLContext dsl, String graphName) {
        var arguments = argumentsBy(dsl, graphName, GRAPHQL_TYPE_DIRECTIVE_ARG,
            "TYPE_NAME", "DIRECTIVE_NAME", "ORDINAL");
        for (var row : dsl.selectFrom(GRAPHQL_TYPE_DIRECTIVE)
                .where(GRAPHQL_TYPE_DIRECTIVE.GRAPH_NAME.eq(graphName))
                .orderBy(GRAPHQL_TYPE_DIRECTIVE.TYPE_NAME, GRAPHQL_TYPE_DIRECTIVE.DIRECTIVE_NAME,
                    GRAPHQL_TYPE_DIRECTIVE.ORDINAL)
                .fetch()) {
            // The site's location is the declaration the application sits on, which is why this
            // relation carries two positions: the declaration's, keyed here, and the directive's own.
            var site = new SiteRef(row.getTypeName(), new SourceLocation(
                row.getDeclarationLine(), row.getDeclarationColumn(), row.getSourceName()));
            captureTypeDirective(site,
                directive(row.getDirectiveName(),
                    location(row.getSourceName(), row.getSourceLine(), row.getSourceColumn()),
                    arguments.get(List.of(row.getTypeName(), row.getDirectiveName(), row.getOrdinal()))),
                row.getOrdinal());
        }
    }

    private void fieldDirectives(DSLContext dsl, String graphName) {
        var arguments = argumentsBy(dsl, graphName, GRAPHQL_FIELD_DIRECTIVE_ARG,
            "TYPE_NAME", "FIELD_NAME", "DIRECTIVE_NAME", "ORDINAL");
        // Which coordinates are input fields, from the transcription rather than from a flag the
        // walk carried down: one query answers it for every application below.
        var inputTypes = Set.copyOf(dsl.select(GRAPHQL_TYPE.TYPE_NAME).from(GRAPHQL_TYPE)
            .where(GRAPHQL_TYPE.GRAPH_NAME.eq(graphName))
            .and(GRAPHQL_TYPE.KIND.eq("INPUT_OBJECT"))
            .fetch(GRAPHQL_TYPE.TYPE_NAME));
        for (var row : dsl.selectFrom(GRAPHQL_FIELD_DIRECTIVE)
                .where(GRAPHQL_FIELD_DIRECTIVE.GRAPH_NAME.eq(graphName))
                .orderBy(GRAPHQL_FIELD_DIRECTIVE.TYPE_NAME, GRAPHQL_FIELD_DIRECTIVE.FIELD_NAME,
                    GRAPHQL_FIELD_DIRECTIVE.DIRECTIVE_NAME, GRAPHQL_FIELD_DIRECTIVE.ORDINAL)
                .fetch()) {
            captureFieldDirective(row.getTypeName(), row.getFieldName(),
                directive(row.getDirectiveName(),
                    location(row.getSourceName(), row.getSourceLine(), row.getSourceColumn()),
                    arguments.get(List.of(row.getTypeName(), row.getFieldName(),
                        row.getDirectiveName(), row.getOrdinal()))),
                row.getOrdinal(), inputTypes.contains(row.getTypeName()));
        }
    }

    private void argumentDirectives(DSLContext dsl, String graphName) {
        var arguments = argumentsBy(dsl, graphName, GRAPHQL_ARGUMENT_DIRECTIVE_ARG,
            "TYPE_NAME", "FIELD_NAME", "ARGUMENT_NAME", "DIRECTIVE_NAME", "ORDINAL");
        for (var row : dsl.selectFrom(GRAPHQL_ARGUMENT_DIRECTIVE)
                .where(GRAPHQL_ARGUMENT_DIRECTIVE.GRAPH_NAME.eq(graphName))
                .orderBy(GRAPHQL_ARGUMENT_DIRECTIVE.TYPE_NAME, GRAPHQL_ARGUMENT_DIRECTIVE.FIELD_NAME,
                    GRAPHQL_ARGUMENT_DIRECTIVE.ARGUMENT_NAME,
                    GRAPHQL_ARGUMENT_DIRECTIVE.DIRECTIVE_NAME, GRAPHQL_ARGUMENT_DIRECTIVE.ORDINAL)
                .fetch()) {
            captureArgumentDirective(row.getTypeName(), row.getFieldName(), row.getArgumentName(),
                directive(row.getDirectiveName(),
                    location(row.getSourceName(), row.getSourceLine(), row.getSourceColumn()),
                    arguments.get(List.of(row.getTypeName(), row.getFieldName(),
                        row.getArgumentName(), row.getDirectiveName(), row.getOrdinal()))),
                row.getOrdinal());
        }
    }

    private void enumValueDirectives(DSLContext dsl, String graphName) {
        var arguments = argumentsBy(dsl, graphName, GRAPHQL_ENUM_VALUE_DIRECTIVE_ARG,
            "TYPE_NAME", "VALUE_NAME", "DIRECTIVE_NAME", "ORDINAL");
        for (var row : dsl.selectFrom(GRAPHQL_ENUM_VALUE_DIRECTIVE)
                .where(GRAPHQL_ENUM_VALUE_DIRECTIVE.GRAPH_NAME.eq(graphName))
                .orderBy(GRAPHQL_ENUM_VALUE_DIRECTIVE.TYPE_NAME, GRAPHQL_ENUM_VALUE_DIRECTIVE.VALUE_NAME,
                    GRAPHQL_ENUM_VALUE_DIRECTIVE.DIRECTIVE_NAME, GRAPHQL_ENUM_VALUE_DIRECTIVE.ORDINAL)
                .fetch()) {
            captureEnumValueDirective(row.getTypeName(), row.getValueName(),
                directive(row.getDirectiveName(),
                    location(row.getSourceName(), row.getSourceLine(), row.getSourceColumn()),
                    arguments.get(List.of(row.getTypeName(), row.getValueName(),
                        row.getDirectiveName(), row.getOrdinal()))),
                row.getOrdinal());
        }
    }

    /**
     * Every application's authored arguments in one query, grouped by the coordinate columns named:
     * the alternative is a query per application, and a consumer schema carries tens of thousands.
     */
    private static Map<List<Object>, Map<String, String>> argumentsBy(
        DSLContext dsl, String graphName, Table<?> table, String... keyColumns
    ) {
        var grouped = new LinkedHashMap<List<Object>, Map<String, String>>();
        for (Record row : dsl.selectFrom(table)
                .where(table.field("GRAPH_NAME", String.class).eq(graphName))
                .fetch()) {
            var key = new ArrayList<>(keyColumns.length);
            for (String column : keyColumns) {
                key.add(row.get(column));
            }
            grouped.computeIfAbsent(key, k -> new LinkedHashMap<>())
                .put(row.get("DIRECTIVE_ARGUMENT_NAME", String.class),
                    row.get("VALUE_SDL", String.class));
        }
        return grouped;
    }

    /**
     * Rebuilds one application from its stored columns. An argument whose literal does not parse
     * back quarantines and is left off, which lands the decode on the same path an argument the
     * author never wrote takes: a NULL column, and the authored text preserved beside it.
     */
    private Directive directive(String name, SourceLocation location, Map<String, String> stored) {
        var arguments = new ArrayList<Argument>();
        if (stored != null) {
            stored.forEach((argumentName, valueSdl) -> {
                Value<?> value = parsed(valueSdl);
                if (value == null) {
                    undecoded(name, location, argumentName, valueSdl);
                } else {
                    arguments.add(Argument.newArgument().name(argumentName).value(value).build());
                }
            });
        }
        return Directive.newDirective().name(name).arguments(arguments)
            .sourceLocation(location).build();
    }

    private static Value<?> parsed(String valueSdl) {
        try {
            return Parser.parseValue(valueSdl);
        } catch (InvalidSyntaxException e) {
            return null;
        }
    }

    /** A position from the three columns that hold one, or none where the store recorded none. */
    private static SourceLocation location(String sourceName, Integer line, Integer column) {
        return line == null || column == null ? null : new SourceLocation(line, column, sourceName);
    }

    // ---------------------------------------------------------------- schema-level

    void captureSchemaDirective(Directive directive, int ordinal) {
        if (!FEDERATION_LINK.equals(directive.getName())) {
            return;
        }
        if (!sink.claim(GRAPHITRON_LINK, ordinal)) {
            return;
        }
        var record = sink.dsl().newRecord(GRAPHITRON_LINK);
        record.setOrdinal(ordinal);
        SdlFactCapture.setPosition(directive.getSourceLocation(),
            record::setSourceName, record::setSourceLine, record::setSourceColumn);
        record.setUrl(string(directive, "url"));
        sink.add(record);

        int position = 0;
        for (Value<?> element : list(directive, "import")) {
            String name;
            String alias = null;
            if (element instanceof ObjectValue object) {
                name = stringOf(field(object, "name"), directive, "import");
                alias = stringOf(field(object, "as"), directive, "import");
            } else {
                name = stringOf(element, directive, "import");
            }
            if (name == null) {
                continue;
            }
            var row = sink.dsl().newRecord(GRAPHITRON_LINK_IMPORT);
            row.setLinkOrdinal(ordinal);
            row.setPosition(position++);
            row.setName(name);
            row.setAlias(alias);
            sink.add(row);
        }
    }

    // ---------------------------------------------------------------- type-level

    /**
     * Type-coordinate relations carry the applying declaration site, because an extension applies
     * {@code @table} or {@code @key} as readily as a base definition does. A repeated application
     * of a single-application directive keeps the first row; the repeat is a detection over the
     * ordinal, never a collision.
     */
    void captureTypeDirective(SiteRef site, Directive directive, int ordinal) {
        String type = site.typeName();
        switch (directive.getName()) {
            case "table" -> {
                if (!sink.claim(GRAPHITRON_TABLE, type)) return;
                var record = sink.dsl().newRecord(GRAPHITRON_TABLE);
                record.setTypeName(type);
                site(site, directive, record::setSourceName, record::setDeclarationLine,
                    record::setDeclarationColumn, record::setSourceLine, record::setSourceColumn);
                String written = string(directive, "name");
                qualified(written, record::setTableRef,
                    record::setTableRefNamespacePart, record::setTableRefNamePart);
                // The site records what the author wrote, which on a bare @table is nothing; the
                // spelling it resolves against is the type's own name, and that is the fact the
                // supertype holds. Split rather than passed through spelledReference because this
                // is the one site whose spelling is not the value it stores.
                spelling(written == null ? type : written);
                sink.add(record);
            }
            case "scalarType" -> {
                if (!sink.claim(GRAPHITRON_SCALAR_TYPE, type)) return;
                String scalar = string(directive, "scalar");
                if (scalar == null) return;
                var record = sink.dsl().newRecord(GRAPHITRON_SCALAR_TYPE);
                record.setTypeName(type);
                site(site, directive, record::setSourceName, record::setDeclarationLine,
                    record::setDeclarationColumn, record::setSourceLine, record::setSourceColumn);
                record.setScalarRef(scalar);
                sink.add(record);
            }
            case "enum" -> {
                if (!sink.claim(GRAPHITRON_ENUM, type)) return;
                var record = sink.dsl().newRecord(GRAPHITRON_ENUM);
                record.setTypeName(type);
                site(site, directive, record::setSourceName, record::setDeclarationLine,
                    record::setDeclarationColumn, record::setSourceLine, record::setSourceColumn);
                var reference = codeReference(directive, "enumReference");
                record.setClassName(reference.className());
                record.setMethod(reference.method());
                record.setArgmapping(reference.argMapping());
                sink.add(record);
                methodReference("ENUM", type, type, null, null, null, null,
                    reference.className(), reference.method(), directive);
            }
            case "record" -> {
                if (!sink.claim(GRAPHITRON_RECORD, type)) return;
                var record = sink.dsl().newRecord(GRAPHITRON_RECORD);
                record.setTypeName(type);
                site(site, directive, record::setSourceName, record::setDeclarationLine,
                    record::setDeclarationColumn, record::setSourceLine, record::setSourceColumn);
                record.setClassName(codeReference(directive, "record").className());
                sink.add(record);
            }
            case "error" -> {
                if (!sink.claim(GRAPHITRON_ERROR, type)) return;
                var record = sink.dsl().newRecord(GRAPHITRON_ERROR);
                record.setTypeName(type);
                site(site, directive, record::setSourceName, record::setDeclarationLine,
                    record::setDeclarationColumn, record::setSourceLine, record::setSourceColumn);
                sink.add(record);
                int position = 0;
                for (Value<?> handler : list(directive, "handlers")) {
                    if (!(handler instanceof ObjectValue object)) {
                        undecoded(directive, "handlers", handler);
                        continue;
                    }
                    var row = sink.dsl().newRecord(GRAPHITRON_ERROR_HANDLER);
                    row.setTypeName(type);
                    row.setPosition(position++);
                    row.setHandler(tokenOf(field(object, "handler")));
                    row.setClassName(stringOf(field(object, "className"), directive, "handlers"));
                    row.setCode(stringOf(field(object, "code"), directive, "handlers"));
                    row.setSqlState(stringOf(field(object, "sqlState"), directive, "handlers"));
                    row.setMatches(stringOf(field(object, "matches"), directive, "handlers"));
                    row.setDescription(stringOf(field(object, "description"), directive, "handlers"));
                    sink.add(row);
                }
            }
            case "node" -> {
                if (!sink.claim(GRAPHITRON_NODE_ENTRY, type)) return;
                var record = sink.dsl().newRecord(GRAPHITRON_NODE_ENTRY);
                record.setTypeName(type);
                site(site, directive, record::setSourceName, record::setDeclarationLine,
                    record::setDeclarationColumn, record::setSourceLine, record::setSourceColumn);
                record.setTypeId(string(directive, "typeId"));
                sink.add(record);
                int position = 0;
                for (Value<?> column : list(directive, "keyColumns")) {
                    String name = stringOf(column, directive, "keyColumns");
                    if (name == null) continue;
                    var row = sink.dsl().newRecord(GRAPHITRON_NODE_KEYCOLUMN_ENTRY);
                    row.setTypeName(type);
                    row.setPosition(position++);
                    row.setColumnRef(name);
                    sink.add(row);
                }
            }
            case "discriminate" -> {
                if (!sink.claim(GRAPHITRON_DISCRIMINATE, type)) return;
                String on = string(directive, "on");
                if (on == null) return;
                var record = sink.dsl().newRecord(GRAPHITRON_DISCRIMINATE);
                record.setTypeName(type);
                site(site, directive, record::setSourceName, record::setDeclarationLine,
                    record::setDeclarationColumn, record::setSourceLine, record::setSourceColumn);
                record.setOnColumn(on);
                sink.add(record);
            }
            case "discriminator" -> {
                if (!sink.claim(GRAPHITRON_DISCRIMINATOR, type)) return;
                String value = string(directive, "value");
                if (value == null) return;
                var record = sink.dsl().newRecord(GRAPHITRON_DISCRIMINATOR);
                record.setTypeName(type);
                site(site, directive, record::setSourceName, record::setDeclarationLine,
                    record::setDeclarationColumn, record::setSourceLine, record::setSourceColumn);
                record.setDiscriminatorValue(value);
                sink.add(record);
            }
            case FEDERATION_KEY -> captureFederationKey(site, directive, ordinal);
            default -> { /* no decoded relation: fidelity-only or consumer-less */ }
        }
    }

    /**
     * Federation's {@code @key}, decoded for consumption. Its verbatim twin lives in
     * {@code graphql_type_directive} for re-emission and both are written in the same pass, so a
     * gate query can pin the two projections in agreement.
     */
    private void captureFederationKey(SiteRef site, Directive directive, int ordinal) {
        if (!sink.claim(GRAPHITRON_FEDERATION_KEY, site.typeName(), ordinal)) return;
        String fields = string(directive, "fields");
        if (fields == null) return;
        var record = sink.dsl().newRecord(GRAPHITRON_FEDERATION_KEY);
        record.setTypeName(site.typeName());
        record.setOrdinal(ordinal);
        site(site, directive, record::setSourceName, record::setDeclarationLine,
            record::setDeclarationColumn, record::setSourceLine, record::setSourceColumn);
        record.setFieldsSdl(fields);
        record.setResolvable(bool(directive, "resolvable"));
        sink.add(record);

        int position = 0;
        for (List<String> path : FieldSetGrammar.paths(fields)) {
            var row = sink.dsl().newRecord(GRAPHITRON_FEDERATION_KEY_FIELD);
            row.setTypeName(site.typeName());
            row.setOrdinal(ordinal);
            row.setPosition(position);
            sink.add(row);
            for (int segment = 0; segment < path.size(); segment++) {
                var segmentRow = sink.dsl().newRecord(GRAPHITRON_FEDERATION_KEY_FIELD_SEGMENT);
                segmentRow.setTypeName(site.typeName());
                segmentRow.setOrdinal(ordinal);
                segmentRow.setPosition(position);
                segmentRow.setSegmentPosition(segment);
                segmentRow.setSegmentName(path.get(segment));
                sink.add(segmentRow);
            }
            position++;
        }
    }

    // ---------------------------------------------------------------- field-level

    /**
     * @param inputField whether the coordinate is a field of an INPUT_OBJECT rather than of an
     *     object or interface. Carried in because a condition on an input field is a different
     *     argMapping site from one on an object field, and the type's kind is what separates them;
     *     the walk knows which of the two it is descending and nothing at this depth does
     */
    void captureFieldDirective(String type, String field, Directive directive, int ordinal,
                               boolean inputField) {
        switch (directive.getName()) {
            case "field" -> {
                if (!sink.claim(GRAPHITRON_FIELD_BINDING, type, field)) return;
                String name = string(directive, "name");
                if (name == null) return;
                var record = sink.dsl().newRecord(GRAPHITRON_FIELD_BINDING);
                record.setTypeName(type);
                record.setFieldName(field);
                position(directive, record::setSourceName, record::setSourceLine, record::setSourceColumn);
                record.setNameRef(name);
                sink.add(record);
            }
            case "condition" -> {
                if (!sink.claim(GRAPHITRON_FIELD_CONDITION, type, field)) return;
                var reference = codeReference(directive, "condition");
                var record = sink.dsl().newRecord(GRAPHITRON_FIELD_CONDITION);
                record.setTypeName(type);
                record.setFieldName(field);
                position(directive, record::setSourceName, record::setSourceLine, record::setSourceColumn);
                record.setClassName(reference.className());
                record.setMethod(reference.method());
                record.setArgmapping(reference.argMapping());
                record.setOverride(bool(directive, "override"));
                sink.add(record);
                methodReference(inputField ? "INPUT_FIELD_CONDITION" : "FIELD_CONDITION",
                    useSite(type, field, null, null, null), type, field, null, null, null,
                    reference.className(), reference.method(), directive);
                int position = 0;
                for (Value<?> context : list(directive, "contextArguments")) {
                    String name = stringOf(context, directive, "contextArguments");
                    if (name == null) continue;
                    var row = sink.dsl().newRecord(GRAPHITRON_FIELD_CONDITION_CONTEXT_ARG);
                    row.setTypeName(type);
                    row.setFieldName(field);
                    row.setPosition(position++);
                    row.setName(name);
                    sink.add(row);
                }
                int pair = 0;
                for (ParsedEntry entry : pairs(reference.argMapping(), directive, "condition")) {
                    int at = pair++;
                    String path = String.join(".", entry.segments());
                    argMappingPair(inputField ? "INPUT_FIELD_CONDITION" : "FIELD_CONDITION",
                        useSite(type, field, null, null, null), type, field, null, null, null,
                        at, entry.key(), path, directive);
                }
            }
            case "reference" -> {
                if (!sink.claim(GRAPHITRON_FIELD_REFERENCE, type, field, ordinal)) return;
                var record = sink.dsl().newRecord(GRAPHITRON_FIELD_REFERENCE);
                record.setTypeName(type);
                record.setFieldName(field);
                record.setOrdinal(ordinal);
                position(directive, record::setSourceName, record::setSourceLine, record::setSourceColumn);
                sink.add(record);
                int position = 0;
                for (Value<?> element : list(directive, "path")) {
                    var step = referenceElement(element, directive);
                    if (step == null) continue;
                    var row = sink.dsl().newRecord(GRAPHITRON_FIELD_REFERENCE_STEP);
                    row.setTypeName(type);
                    row.setFieldName(field);
                    row.setOrdinal(ordinal);
                    row.setPosition(position);
                    spelledReference(step.table(), row::setTableRef,
                        row::setTableRefNamespacePart, row::setTableRefNamePart);
                    qualified(step.key(), row::setKeyRef,
                        row::setKeyRefNamespacePart, row::setKeyRefNamePart);
                    row.setClassName(step.className());
                    row.setMethod(step.method());
                    row.setArgmapping(step.argMapping());
                    sink.add(row);
                    methodReference("FIELD_REFERENCE_STEP", useSite(type, field, null, ordinal, position), type, field, null,
                        ordinal, position,
                        step.className(), step.method(), directive);
                    int pair = 0;
                    for (ParsedEntry entry : pairs(step.argMapping(), directive, "path")) {
                        int at = pair++;
                        String path = String.join(".", entry.segments());
                        argMappingPair("FIELD_REFERENCE_STEP",
                            useSite(type, field, null, ordinal, position), type, field, null,
                            ordinal, position, at, entry.key(), path, directive);
                    }
                    position++;
                }
            }
            case "referenceFor" -> {
                if (!sink.claim(GRAPHITRON_REFERENCE_FOR, type, field, ordinal)) return;
                String participant = string(directive, "type");
                if (participant == null) return;
                var record = sink.dsl().newRecord(GRAPHITRON_REFERENCE_FOR);
                record.setTypeName(type);
                record.setFieldName(field);
                record.setOrdinal(ordinal);
                position(directive, record::setSourceName, record::setSourceLine, record::setSourceColumn);
                record.setParticipantTypeRef(participant);
                sink.add(record);
                int position = 0;
                for (Value<?> element : list(directive, "path")) {
                    var step = referenceElement(element, directive);
                    if (step == null) continue;
                    var row = sink.dsl().newRecord(GRAPHITRON_REFERENCE_FOR_STEP);
                    row.setTypeName(type);
                    row.setFieldName(field);
                    row.setOrdinal(ordinal);
                    row.setPosition(position);
                    spelledReference(step.table(), row::setTableRef,
                        row::setTableRefNamespacePart, row::setTableRefNamePart);
                    qualified(step.key(), row::setKeyRef,
                        row::setKeyRefNamespacePart, row::setKeyRefNamePart);
                    row.setClassName(step.className());
                    row.setMethod(step.method());
                    row.setArgmapping(step.argMapping());
                    sink.add(row);
                    methodReference("REFERENCE_FOR_STEP", useSite(type, field, null, ordinal, position), type, field, null,
                        ordinal, position,
                        step.className(), step.method(), directive);
                    int pair = 0;
                    for (ParsedEntry entry : pairs(step.argMapping(), directive, "path")) {
                        int at = pair++;
                        String path = String.join(".", entry.segments());
                        argMappingPair("REFERENCE_FOR_STEP",
                            useSite(type, field, null, ordinal, position), type, field, null,
                            ordinal, position, at, entry.key(), path, directive);
                    }
                    position++;
                }
            }
            case "service" -> {
                if (!sink.claim(GRAPHITRON_SERVICE, type, field)) return;
                var reference = codeReference(directive, "service");
                var record = sink.dsl().newRecord(GRAPHITRON_SERVICE);
                record.setTypeName(type);
                record.setFieldName(field);
                position(directive, record::setSourceName, record::setSourceLine, record::setSourceColumn);
                record.setClassName(reference.className());
                record.setMethod(reference.method());
                record.setArgmapping(reference.argMapping());
                sink.add(record);
                methodReference("SERVICE", useSite(type, field, null, null, null),
                    type, field, null, null, null,
                    reference.className(), reference.method(), directive);
                int position = 0;
                for (Value<?> context : list(directive, "contextArguments")) {
                    String name = stringOf(context, directive, "contextArguments");
                    if (name == null) continue;
                    var row = sink.dsl().newRecord(GRAPHITRON_SERVICE_CONTEXT_ARG);
                    row.setTypeName(type);
                    row.setFieldName(field);
                    row.setPosition(position++);
                    row.setName(name);
                    sink.add(row);
                }
                // The @service argMapping is the sigil-admitting site. A sigil entry is an entry
                // like any other, a parameter bound to a right-hand side at a position, so it is a
                // row of the one entry relation rather than of a relation beside it; what the lift
                // exists for is the shared selection parser, which lexes a $-prefixed value and
                // rejects it. Positions come from the scan because the lift takes entries out of
                // the middle, so the residual's own numbering is not the author's.
                var scanned = ArgMappingSigil.scan(reference.argMapping(), ArgMappingSigil.Site.SERVICE);
                String residual = reference.argMapping();
                var sigilPositions = new java.util.LinkedHashMap<String, Integer>();
                if (scanned instanceof ArgMappingSigil.ScanResult.Ok scanOk) {
                    residual = scanOk.residual();
                    sigilPositions.putAll(scanOk.sigilPositions());
                    for (var sigilEntry : scanOk.sigilBindings().entrySet()) {
                        int at = scanOk.sigilPositions().get(sigilEntry.getKey());
                        argMappingPair("SERVICE", useSite(type, field, null, null, null),
                            type, field, null, null, null, at,
                            sigilEntry.getKey(), sigilEntry.getValue(), directive);
                    }
                }
                var taken = java.util.Set.copyOf(sigilPositions.values());
                int pair = 0;
                for (ParsedEntry entry : pairs(residual, directive, "service")) {
                    while (taken.contains(pair)) pair++;
                    int at = pair++;
                    String path = String.join(".", entry.segments());
                    argMappingPair("SERVICE", useSite(type, field, null, null, null),
                        type, field, null, null, null, at, entry.key(), path, directive);
                }
            }
            case "externalField" -> {
                if (!sink.claim(GRAPHITRON_EXTERNAL_FIELD, type, field)) return;
                var reference = codeReference(directive, "reference");
                var record = sink.dsl().newRecord(GRAPHITRON_EXTERNAL_FIELD);
                record.setTypeName(type);
                record.setFieldName(field);
                position(directive, record::setSourceName, record::setSourceLine, record::setSourceColumn);
                record.setClassName(reference.className());
                record.setMethod(reference.method());
                record.setArgmapping(reference.argMapping());
                sink.add(record);
                methodReference("EXTERNAL_FIELD", useSite(type, field, null, null, null),
                    type, field, null, null, null,
                    reference.className(), reference.method(), directive);
            }
            case "sourceRow" -> {
                // No relation of its own: this site carried the class and the method and nothing
                // else, so it is rows of the shared relation rather than a table beside it.
                if (!sink.claim(GRAPHITRON_METHOD_REFERENCE, "SOURCE_ROW",
                        useSite(type, field, null, null, null))) return;
                methodReference("SOURCE_ROW", useSite(type, field, null, null, null),
                    type, field, null, null, null,
                    string(directive, "className"), string(directive, "method"), directive);
            }
            case "asConnection" -> {
                if (!sink.claim(GRAPHITRON_CONNECTION, type, field)) return;
                var record = sink.dsl().newRecord(GRAPHITRON_CONNECTION);
                record.setTypeName(type);
                record.setFieldName(field);
                position(directive, record::setSourceName, record::setSourceLine, record::setSourceColumn);
                record.setDefaultFirstValue(integer(directive, "defaultFirstValue"));
                record.setConnectionName(string(directive, "connectionName"));
                sink.add(record);
            }
            case "asFacet" -> marker(GRAPHITRON_FACET, type, field, directive);
            case "splitQuery" -> marker(GRAPHITRON_SPLIT_QUERY, type, field, directive);
            case "tenantFanOut" -> marker(GRAPHITRON_TENANT_FAN_OUT, type, field, directive);
            case "multitableReference" -> marker(GRAPHITRON_MULTITABLE_REFERENCE, type, field, directive);
            case "lookupKey" -> marker(GRAPHITRON_FIELD_LOOKUP_KEY, type, field, directive);
            case "nodeId" -> {
                if (!sink.claim(GRAPHITRON_FIELD_NODE_ID, type, field)) return;
                var record = sink.dsl().newRecord(GRAPHITRON_FIELD_NODE_ID);
                record.setTypeName(type);
                record.setFieldName(field);
                position(directive, record::setSourceName, record::setSourceLine, record::setSourceColumn);
                record.setNodeTypeRef(string(directive, "typeName"));
                sink.add(record);
            }
            case "mutation" -> {
                if (!sink.claim(GRAPHITRON_MUTATION, type, field)) return;
                String operation = tokenOf(argument(directive, "typeName"));
                if (operation == null) return;
                var record = sink.dsl().newRecord(GRAPHITRON_MUTATION);
                record.setTypeName(type);
                record.setFieldName(field);
                position(directive, record::setSourceName, record::setSourceLine, record::setSourceColumn);
                record.setOperation(operation);
                record.setMultiRow(bool(directive, "multiRow"));
                spelledReference(string(directive, "table"), record::setTableRef,
                    record::setTableRefNamespacePart, record::setTableRefNamePart);
                sink.add(record);
            }
            case "pivot" -> {
                if (!sink.claim(GRAPHITRON_PIVOT, type, field)) return;
                String on = string(directive, "on");
                String value = string(directive, "value");
                if (on == null || value == null) return;
                var record = sink.dsl().newRecord(GRAPHITRON_PIVOT);
                record.setTypeName(type);
                record.setFieldName(field);
                position(directive, record::setSourceName, record::setSourceLine, record::setSourceColumn);
                record.setOnColumn(on);
                record.setValueColumn(value);
                record.setVocabularyRef(string(directive, "vocabulary"));
                sink.add(record);
            }
            case "defaultOrder" -> {
                if (!sink.claim(GRAPHITRON_DEFAULT_ORDER, type, field)) return;
                var record = sink.dsl().newRecord(GRAPHITRON_DEFAULT_ORDER);
                record.setTypeName(type);
                record.setFieldName(field);
                position(directive, record::setSourceName, record::setSourceLine, record::setSourceColumn);
                record.setIndexRef(string(directive, "index"));
                record.setPrimaryKey_(bool(directive, "primaryKey"));
                record.setDirection(tokenOf(argument(directive, "direction")));
                sink.add(record);
                int position = 0;
                for (Value<?> entry : list(directive, "fields")) {
                    if (!(entry instanceof ObjectValue object)) {
                        undecoded(directive, "fields", entry);
                        continue;
                    }
                    String name = stringOf(field(object, "name"), directive, "fields");
                    if (name == null) continue;
                    var row = sink.dsl().newRecord(GRAPHITRON_DEFAULT_ORDER_FIELD);
                    row.setTypeName(type);
                    row.setFieldName(field);
                    row.setPosition(position++);
                    row.setNameRef(name);
                    row.setCollate(stringOf(field(object, "collate"), directive, "fields"));
                    row.setDirection(tokenOf(field(object, "direction")));
                    sink.add(row);
                }
            }
            case "routine" -> {
                if (!sink.claim(GRAPHITRON_ROUTINE, type, field, ordinal)) return;
                String name = string(directive, "name");
                if (name == null) return;
                String argMapping = string(directive, "argMapping");
                String columnMapping = string(directive, "columnMapping");
                var record = sink.dsl().newRecord(GRAPHITRON_ROUTINE);
                record.setTypeName(type);
                record.setFieldName(field);
                record.setOrdinal(ordinal);
                position(directive, record::setSourceName, record::setSourceLine, record::setSourceColumn);
                spelledReference(name, record::setRoutineRef,
                    record::setRoutineRefNamespacePart, record::setRoutineRefNamePart);
                record.setArgmapping(argMapping);
                record.setColumnMapping(columnMapping);
                sink.add(record);
                int pair = 0;
                for (ParsedEntry entry : pairs(argMapping, directive, "argMapping")) {
                    int at = pair++;
                    String path = String.join(".", entry.segments());
                    argMappingPair("ROUTINE", useSite(type, field, null, ordinal, null),
                        type, field, null, ordinal, null, at, entry.key(), path, directive);
                }
                int column = 0;
                for (ParsedEntry entry : pairs(columnMapping, directive, "columnMapping")) {
                    var row = sink.dsl().newRecord(GRAPHITRON_ROUTINE_COLUMN_MAPPING_PAIR);
                    row.setTypeName(type);
                    row.setFieldName(field);
                    row.setOrdinal(ordinal);
                    row.setPosition(column++);
                    row.setParamName(entry.key());
                    row.setColumnRef(String.join(".", entry.segments()));
                    sink.add(row);
                }
            }
            default -> { /* no decoded relation */ }
        }
    }

    // ---------------------------------------------------------------- argument-level

    void captureArgumentDirective(String type, String field, String argument,
                                  Directive directive, int ordinal) {
        switch (directive.getName()) {
            case "field" -> {
                if (!sink.claim(GRAPHITRON_ARGUMENT_BINDING, type, field, argument)) return;
                String name = string(directive, "name");
                if (name == null) return;
                var record = sink.dsl().newRecord(GRAPHITRON_ARGUMENT_BINDING);
                record.setTypeName(type);
                record.setFieldName(field);
                record.setArgumentName(argument);
                position(directive, record::setSourceName, record::setSourceLine, record::setSourceColumn);
                record.setNameRef(name);
                sink.add(record);
            }
            case "condition" -> {
                if (!sink.claim(GRAPHITRON_ARGUMENT_CONDITION, type, field, argument)) return;
                var reference = codeReference(directive, "condition");
                var record = sink.dsl().newRecord(GRAPHITRON_ARGUMENT_CONDITION);
                record.setTypeName(type);
                record.setFieldName(field);
                record.setArgumentName(argument);
                position(directive, record::setSourceName, record::setSourceLine, record::setSourceColumn);
                record.setClassName(reference.className());
                record.setMethod(reference.method());
                record.setArgmapping(reference.argMapping());
                record.setOverride(bool(directive, "override"));
                sink.add(record);
                methodReference("ARGUMENT_CONDITION", useSite(type, field, argument, null, null),
                    type, field, argument, null, null,
                    reference.className(), reference.method(), directive);
                int position = 0;
                for (Value<?> context : list(directive, "contextArguments")) {
                    String name = stringOf(context, directive, "contextArguments");
                    if (name == null) continue;
                    var row = sink.dsl().newRecord(GRAPHITRON_ARGUMENT_CONDITION_CONTEXT_ARG);
                    row.setTypeName(type);
                    row.setFieldName(field);
                    row.setArgumentName(argument);
                    row.setPosition(position++);
                    row.setName(name);
                    sink.add(row);
                }
                int pair = 0;
                for (ParsedEntry entry : pairs(reference.argMapping(), directive, "condition")) {
                    int at = pair++;
                    String path = String.join(".", entry.segments());
                    argMappingPair("ARGUMENT_CONDITION", useSite(type, field, argument, null, null),
                        type, field, argument, null, null, at, entry.key(), path, directive);
                }
            }
            case "reference" -> {
                if (!sink.claim(GRAPHITRON_ARGUMENT_REFERENCE, type, field, argument, ordinal)) return;
                var record = sink.dsl().newRecord(GRAPHITRON_ARGUMENT_REFERENCE);
                record.setTypeName(type);
                record.setFieldName(field);
                record.setArgumentName(argument);
                record.setOrdinal(ordinal);
                position(directive, record::setSourceName, record::setSourceLine, record::setSourceColumn);
                sink.add(record);
                int position = 0;
                for (Value<?> element : list(directive, "path")) {
                    var step = referenceElement(element, directive);
                    if (step == null) continue;
                    var row = sink.dsl().newRecord(GRAPHITRON_ARGUMENT_REFERENCE_STEP);
                    row.setTypeName(type);
                    row.setFieldName(field);
                    row.setArgumentName(argument);
                    row.setOrdinal(ordinal);
                    row.setPosition(position);
                    spelledReference(step.table(), row::setTableRef,
                        row::setTableRefNamespacePart, row::setTableRefNamePart);
                    qualified(step.key(), row::setKeyRef,
                        row::setKeyRefNamespacePart, row::setKeyRefNamePart);
                    row.setClassName(step.className());
                    row.setMethod(step.method());
                    row.setArgmapping(step.argMapping());
                    sink.add(row);
                    methodReference("ARGUMENT_REFERENCE_STEP", useSite(type, field, argument, ordinal, position), type, field, argument,
                        ordinal, position,
                        step.className(), step.method(), directive);
                    int pair = 0;
                    for (ParsedEntry entry : pairs(step.argMapping(), directive, "path")) {
                        int at = pair++;
                        String path = String.join(".", entry.segments());
                        argMappingPair("ARGUMENT_REFERENCE_STEP",
                            useSite(type, field, argument, ordinal, position), type, field, argument,
                            ordinal, position, at, entry.key(), path, directive);
                    }
                    position++;
                }
            }
            case "referenceFor" -> {
                if (!sink.claim(GRAPHITRON_ARGUMENT_REFERENCE_FOR, type, field, argument, ordinal)) return;
                String participant = string(directive, "type");
                if (participant == null) return;
                var record = sink.dsl().newRecord(GRAPHITRON_ARGUMENT_REFERENCE_FOR);
                record.setTypeName(type);
                record.setFieldName(field);
                record.setArgumentName(argument);
                record.setOrdinal(ordinal);
                position(directive, record::setSourceName, record::setSourceLine, record::setSourceColumn);
                record.setParticipantTypeRef(participant);
                sink.add(record);
                int position = 0;
                for (Value<?> element : list(directive, "path")) {
                    var step = referenceElement(element, directive);
                    if (step == null) continue;
                    var row = sink.dsl().newRecord(GRAPHITRON_ARGUMENT_REFERENCE_FOR_STEP);
                    row.setTypeName(type);
                    row.setFieldName(field);
                    row.setArgumentName(argument);
                    row.setOrdinal(ordinal);
                    row.setPosition(position);
                    spelledReference(step.table(), row::setTableRef,
                        row::setTableRefNamespacePart, row::setTableRefNamePart);
                    qualified(step.key(), row::setKeyRef,
                        row::setKeyRefNamespacePart, row::setKeyRefNamePart);
                    row.setClassName(step.className());
                    row.setMethod(step.method());
                    row.setArgmapping(step.argMapping());
                    sink.add(row);
                    methodReference("ARGUMENT_REFERENCE_FOR_STEP", useSite(type, field, argument, ordinal, position), type, field, argument,
                        ordinal, position,
                        step.className(), step.method(), directive);
                    int pair = 0;
                    for (ParsedEntry entry : pairs(step.argMapping(), directive, "path")) {
                        int at = pair++;
                        String path = String.join(".", entry.segments());
                        argMappingPair("ARGUMENT_REFERENCE_FOR_STEP",
                            useSite(type, field, argument, ordinal, position), type, field, argument,
                            ordinal, position, at, entry.key(), path, directive);
                    }
                    position++;
                }
            }
            case "nodeId" -> {
                if (!sink.claim(GRAPHITRON_ARGUMENT_NODE_ID, type, field, argument)) return;
                var record = sink.dsl().newRecord(GRAPHITRON_ARGUMENT_NODE_ID);
                record.setTypeName(type);
                record.setFieldName(field);
                record.setArgumentName(argument);
                position(directive, record::setSourceName, record::setSourceLine, record::setSourceColumn);
                record.setNodeTypeRef(string(directive, "typeName"));
                sink.add(record);
            }
            case "lookupKey" -> {
                if (!sink.claim(GRAPHITRON_ARGUMENT_LOOKUP_KEY, type, field, argument)) return;
                var record = sink.dsl().newRecord(GRAPHITRON_ARGUMENT_LOOKUP_KEY);
                record.setTypeName(type);
                record.setFieldName(field);
                record.setArgumentName(argument);
                position(directive, record::setSourceName, record::setSourceLine, record::setSourceColumn);
                sink.add(record);
            }
            case "orderBy" -> {
                if (!sink.claim(GRAPHITRON_ORDER_BY, type, field, argument)) return;
                var record = sink.dsl().newRecord(GRAPHITRON_ORDER_BY);
                record.setTypeName(type);
                record.setFieldName(field);
                record.setArgumentName(argument);
                position(directive, record::setSourceName, record::setSourceLine, record::setSourceColumn);
                sink.add(record);
            }
            default -> { /* no decoded relation */ }
        }
    }

    // ---------------------------------------------------------------- enum-value-level

    void captureEnumValueDirective(String type, String value, Directive directive, int ordinal) {
        switch (directive.getName()) {
            case "field" -> {
                if (!sink.claim(GRAPHITRON_ENUM_VALUE_BINDING, type, value)) return;
                String name = string(directive, "name");
                if (name == null) return;
                var record = sink.dsl().newRecord(GRAPHITRON_ENUM_VALUE_BINDING);
                record.setTypeName(type);
                record.setValueName(value);
                position(directive, record::setSourceName, record::setSourceLine, record::setSourceColumn);
                record.setNameRef(name);
                sink.add(record);
            }
            case "index" -> {
                if (!sink.claim(GRAPHITRON_INDEX, type, value)) return;
                var record = sink.dsl().newRecord(GRAPHITRON_INDEX);
                record.setTypeName(type);
                record.setValueName(value);
                position(directive, record::setSourceName, record::setSourceLine, record::setSourceColumn);
                record.setIndexRef(string(directive, "name"));
                sink.add(record);
            }
            case "order" -> {
                if (!sink.claim(GRAPHITRON_ORDER, type, value)) return;
                var record = sink.dsl().newRecord(GRAPHITRON_ORDER);
                record.setTypeName(type);
                record.setValueName(value);
                position(directive, record::setSourceName, record::setSourceLine, record::setSourceColumn);
                record.setIndexRef(string(directive, "index"));
                record.setPrimaryKey_(bool(directive, "primaryKey"));
                sink.add(record);
                int position = 0;
                for (Value<?> entry : list(directive, "fields")) {
                    if (!(entry instanceof ObjectValue object)) {
                        undecoded(directive, "fields", entry);
                        continue;
                    }
                    String name = stringOf(field(object, "name"), directive, "fields");
                    if (name == null) continue;
                    var row = sink.dsl().newRecord(GRAPHITRON_ORDER_FIELD);
                    row.setTypeName(type);
                    row.setValueName(value);
                    row.setPosition(position++);
                    row.setNameRef(name);
                    row.setCollate(stringOf(field(object, "collate"), directive, "fields"));
                    row.setDirection(tokenOf(field(object, "direction")));
                    sink.add(row);
                }
            }
            default -> { /* no decoded relation */ }
        }
    }

    // ---------------------------------------------------------------- shared decoding

    /** A marker relation: the coordinate, the application's position, nothing else. */
    private <R extends org.jooq.TableRecord<R>> void marker(
        org.jooq.Table<R> table, String type, String field, Directive directive
    ) {
        if (!sink.claim(table, type, field)) return;
        R record = sink.dsl().newRecord(table);
        record.set(table.field("TYPE_NAME", String.class), type);
        record.set(table.field("FIELD_NAME", String.class), field);
        SourceLocation location = directive.getSourceLocation();
        if (location != null && location.getSourceName() != null) {
            record.set(table.field("SOURCE_NAME", String.class), location.getSourceName());
            record.set(table.field("SOURCE_LINE", Integer.class), location.getLine());
            record.set(table.field("SOURCE_COLUMN", Integer.class), location.getColumn());
        }
        sink.add(record);
    }

    /** The flattened {@code ExternalCodeReference}: three columns, all as written. */
    private record CodeReference(String className, String method, String argMapping) {}

    private CodeReference codeReference(Directive directive, String argumentName) {
        Value<?> value = argument(directive, argumentName);
        if (value == null) {
            return new CodeReference(null, null, null);
        }
        if (!(value instanceof ObjectValue object)) {
            undecoded(directive, argumentName, value);
            return new CodeReference(null, null, null);
        }
        return new CodeReference(
            stringOf(field(object, "className"), directive, argumentName),
            stringOf(field(object, "method"), directive, argumentName),
            stringOf(field(object, "argMapping"), directive, argumentName));
    }

    /** The flattened {@code ReferenceElement}, its step condition inlined. */
    private record Step(String table, String key, String className, String method, String argMapping) {}

    private Step referenceElement(Value<?> value, Directive directive) {
        if (!(value instanceof ObjectValue object)) {
            undecoded(directive, "path", value);
            return null;
        }
        Value<?> condition = field(object, "condition");
        String className = null;
        String method = null;
        String argMapping = null;
        if (condition instanceof ObjectValue conditionObject) {
            className = stringOf(field(conditionObject, "className"), directive, "path");
            method = stringOf(field(conditionObject, "method"), directive, "path");
            argMapping = stringOf(field(conditionObject, "argMapping"), directive, "path");
        } else if (condition != null) {
            undecoded(directive, "path", condition);
        }
        return new Step(
            stringOf(field(object, "table"), directive, "path"),
            stringOf(field(object, "key"), directive, "path"),
            className, method, argMapping);
    }

    /**
     * Decodes an {@code argMapping}-shaped string through the one shared pair decoder. Position
     * keys deliberately preserve an author's duplicate parameter, so the duplicate detection can
     * see it; a value the grammar rejects quarantines whole and yields no pairs.
     */
    private List<ParsedEntry> pairs(String raw, Directive directive, String argumentName) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            return GraphQLSelectionParser.parseEntries(raw);
        } catch (GraphQLSelectionParseException e) {
            undecoded(directive, argumentName, StringValue.newStringValue(raw).build());
            return List.of();
        }
    }

    private static Value<?> argument(Directive directive, String name) {
        Argument argument = directive.getArgument(name);
        return argument == null ? null : argument.getValue();
    }

    private static Value<?> field(ObjectValue object, String name) {
        for (ObjectField field : object.getObjectFields()) {
            if (field.getName().equals(name)) {
                return field.getValue();
            }
        }
        return null;
    }

    private String string(Directive directive, String name) {
        return stringOf(argument(directive, name), directive, name);
    }

    private String stringOf(Value<?> value, Directive directive, String argumentName) {
        if (value == null) {
            return null;
        }
        if (value instanceof StringValue string) {
            return string.getValue();
        }
        undecoded(directive, argumentName, value);
        return null;
    }

    private Integer integer(Directive directive, String name) {
        Value<?> value = argument(directive, name);
        if (value == null) {
            return null;
        }
        if (value instanceof IntValue number) {
            return number.getValue().intValue();
        }
        undecoded(directive, name, value);
        return null;
    }

    private Boolean bool(Directive directive, String name) {
        Value<?> value = argument(directive, name);
        if (value == null) {
            return null;
        }
        if (value instanceof BooleanValue flag) {
            return flag.isValue();
        }
        undecoded(directive, name, value);
        return null;
    }

    private List<Value<?>> list(Directive directive, String name) {
        Value<?> value = argument(directive, name);
        if (value == null) {
            return List.of();
        }
        if (value instanceof ArrayValue array) {
            var values = new java.util.ArrayList<Value<?>>(array.getValues().size());
            for (Value<?> element : array.getValues()) {
                values.add(element);
            }
            return List.copyOf(values);
        }
        undecoded(directive, name, value);
        return List.of();
    }

    /**
     * An author-spelled enum literal: stored as the token the author typed, never checked against
     * a vocabulary. Nothing upstream has validated it under registry capture, so a CHECK would
     * turn a typo into a constraint violation; membership is a detection.
     */
    private static String tokenOf(Value<?> value) {
        return switch (value) {
            case null -> null;
            case EnumValue token -> token.getName();
            case StringValue string -> string.getValue();
            default -> AstPrinter.printAstCompact(value);
        };
    }

    private static void position(Directive directive, Consumer<String> name,
                                 Consumer<Integer> line, Consumer<Integer> column) {
        SdlFactCapture.setPosition(directive.getSourceLocation(), name, line, column);
    }

    /**
     * Writes a qualifiable reference: the value as written, then the two halves of its split. One
     * call rather than three so a site cannot record the value without its decode, which is the
     * invariant every reader of the parts relies on. See {@link QualifiedNameGrammar} for the split.
     */
    private static void qualified(String written, Consumer<String> value,
                                  Consumer<String> namespacePart, Consumer<String> namePart) {
        value.accept(written);
        namespacePart.accept(QualifiedNameGrammar.namespacePart(written));
        namePart.accept(QualifiedNameGrammar.namePart(written));
    }

    /**
     * A qualifiable reference that names a table or a routine, written to its own site's relation
     * and to {@link no.sikt.graphitron.model.Tables#GRAPHITRON_SPELLED_REFERENCE} beside it. The
     * second write is what makes the spelling a fact rather than something a reader reconstructs by
     * unioning the seven relations that carry one; the first is unchanged, each site still recording
     * where its own spelling was written.
     *
     * <p>Not {@link #qualified} itself, and the difference is the subject rather than the shape: a
     * key reference splits by the same grammar and names a constraint, which is a different fact
     * with no supertype of its own. A site calling the wrong one of these two would put constraint
     * names in the catalog-resolution relation, so they are named apart.
     *
     * <p>Deduplicated through {@link FactSink#claim}, because the same spelling authored at five
     * coordinates is one row: the relation is keyed by the spelling and the primary key is what says
     * a second write of it is the same fact and not a second one.
     */
    private void spelledReference(String written, Consumer<String> value,
                                  Consumer<String> namespacePart, Consumer<String> namePart) {
        qualified(written, value, namespacePart, namePart);
        spelling(written);
    }

    /**
     * One spelling into {@link no.sikt.graphitron.model.Tables#GRAPHITRON_SPELLED_REFERENCE},
     * deduplicated through {@link FactSink#claim} because the same spelling authored at five
     * coordinates is one row: the relation is keyed by the spelling and the primary key is what says
     * a second write of it is the same fact and not a second one.
     *
     * <p>Takes the spelling rather than the site's stored value, which are the same string at every
     * site but one. A bare {@code @table} stores nothing, the author having written no name, and
     * resolves against the type's own name; that fallback is a spelling the catalog is matched
     * against, so it belongs here even though no column holds it.
     */
    private void spelling(String written) {
        if (written == null || !sink.claim(GRAPHITRON_SPELLED_REFERENCE, written)) {
            return;
        }
        var row = sink.dsl().newRecord(GRAPHITRON_SPELLED_REFERENCE);
        row.setSpelling(written);
        row.setNamespacePart(QualifiedNameGrammar.namespacePart(written));
        row.setNamePart(QualifiedNameGrammar.namePart(written));
        sink.add(row);
    }

    /**
     * The site spelled in its own grammar, which is what keys the shared relation: the coordinate,
     * then the argument in parentheses where the site sits on one, then the directive application
     * after a hash where the directive repeats, then the path element's position in brackets where
     * the site is one. Every site's spelling is some prefix of that, so one builder produces all
     * nine and no site can spell its own key differently from the rest.
     */
    private static String useSite(String type, String field, String argument,
                                  Integer ordinal, Integer stepPosition) {
        var spelling = new StringBuilder(type).append('.').append(field);
        if (argument != null) {
            spelling.append('(').append(argument).append(')');
        }
        if (ordinal != null) {
            spelling.append('#').append(ordinal);
        }
        if (stepPosition != null) {
            spelling.append('[').append(stepPosition).append(']');
        }
        return spelling.toString();
    }

    /**
     * One argMapping pair, written to the only relation that holds one. Every site states a pair
     * identically, so there is nothing for a per-site relation to carry and none exists; the site
     * is a column here instead. That leaves the reference from a pair back to the directive that
     * spelled it unenforceable, a foreign key not being able to span the nine parents the
     * discriminator chooses between, so this method is where the reference is kept true: the
     * caller is inside the branch that just wrote the owning directive's own row.
     *
     * @param site the discriminator, one of the nine {@code GRAPHITRON_ARGMAPPING_ENTRY} admits
     * @param useSite the site spelled in its own grammar, total by construction and the key
     */
    private void argMappingPair(String site, String useSite, String type, String field,
                                String argument, Integer ordinal, Integer stepPosition,
                                int position, String paramName, String argumentPath,
                                Directive directive) {
        var row = sink.dsl().newRecord(GRAPHITRON_ARGMAPPING_ENTRY);
        row.setSite(site);
        row.setUseSite(useSite);
        row.setOrdinal(ordinal);
        row.setStepPosition(stepPosition);
        row.setPosition(position);
        row.setParamName(paramName);
        row.setWrittenPath(argumentPath);
        // Where the directive sits, and the only spelling of that this relation keeps: a reader
        // wanting the type, the field or the argument joins the coordinate relation, which is the
        // same trade the candidate relation beside it makes. Never a container and never the
        // path's own head, which is a spelling and not a place. Decided by whether the caller
        // named an argument rather than by which site it is, three of the nine sitting on one and
        // six on a field; asking the site instead is a second copy of that fact, and the first
        // version of this writer got it wrong for two of the three. The split columns beside it
        // are the engine's, computed from the written path, so nothing here decomposes anything.
        row.setCoordinate(argument == null
            ? SchemaCoordinateSyntax.ofField(type, field)
            : SchemaCoordinateSyntax.ofArgument(type, field, argument));
        position(directive, row::setSourceName, row::setSourceLine, row::setSourceColumn);
        sink.add(row);
    }

    /**
     * One Java method a directive named, written to the only relation that holds one. The site is a
     * column rather than a relation, so what three intent views were assembling by hand, a union
     * over ten tables projecting the class and the method out of each, is a scan here.
     *
     * <p>Keyed on the same site spelling {@link #argMappingPair} writes, which is what makes a
     * pair and the method it binds into joinable on a key both already carry. A site whose class or
     * method the author left unwritten draws no row: the columns are not nullable, and the readers
     * this replaces all filtered those out before doing anything else, so an absent row states what
     * their {@code IS NOT NULL} did.
     *
     * @param site the discriminator, one of the eleven {@code GRAPHITRON_METHOD_REFERENCE} admits
     */
    private void methodReference(String site, String useSite, String type, String field,
                                 String argument, Integer ordinal, Integer stepPosition,
                                 String className, String method, Directive directive) {
        if (className == null || method == null) {
            return;
        }
        var row = sink.dsl().newRecord(GRAPHITRON_METHOD_REFERENCE);
        row.setSite(site);
        row.setUseSite(useSite);
        row.setTypeName(type);
        row.setFieldName(field);
        row.setArgumentName(argument);
        row.setOrdinal(ordinal);
        row.setStepPosition(stepPosition);
        row.setClassName(className);
        row.setMethod(method);
        position(directive, row::setSourceName, row::setSourceLine, row::setSourceColumn);
        sink.add(row);
    }

    /**
     * Writes the site key (which doubles as the file of the position columns) plus the
     * application's own line and column, the pattern every type-coordinate relation follows.
     */
    private static void site(SiteRef site, Directive directive, Consumer<String> sourceName,
                             Consumer<Integer> declarationLine, Consumer<Integer> declarationColumn,
                             Consumer<Integer> line, Consumer<Integer> column) {
        sourceName.accept(site.location().getSourceName());
        declarationLine.accept(site.location().getLine());
        declarationColumn.accept(site.location().getColumn());
        SdlFactCapture.setOwnPosition(directive.getSourceLocation(), line, column);
    }

    /**
     * Quarantines a literal that does not fit its declared shape, rendered and located, so the
     * authored value survives a decode that produced nothing. Dormant while assembly runs
     * upstream and rejects such schemas first.
     */
    private void undecoded(String directiveName, SourceLocation location, String argumentName,
                           String valueSdl) {
        if (location == null || location.getSourceName() == null) {
            return;
        }
        if (!sink.claim(GRAPHITRON_UNDECODED_ARGUMENT, location.getSourceName(), location.getLine(),
                location.getColumn(), directiveName, argumentName)) {
            return;
        }
        var record = sink.dsl().newRecord(GRAPHITRON_UNDECODED_ARGUMENT);
        record.setSourceName(location.getSourceName());
        record.setSourceLine(location.getLine());
        record.setSourceColumn(location.getColumn());
        record.setDirectiveName(directiveName);
        record.setDirectiveArgumentName(argumentName);
        record.setValueSdl(valueSdl);
        sink.add(record);
    }

    private void undecoded(Directive directive, String argumentName, Value<?> value) {
        SourceLocation location = directive.getSourceLocation();
        if (location == null || location.getSourceName() == null) {
            return;
        }
        if (!sink.claim(GRAPHITRON_UNDECODED_ARGUMENT, location.getSourceName(), location.getLine(),
                location.getColumn(), directive.getName(), argumentName)) {
            return;
        }
        var record = sink.dsl().newRecord(GRAPHITRON_UNDECODED_ARGUMENT);
        record.setSourceName(location.getSourceName());
        record.setSourceLine(location.getLine());
        record.setSourceColumn(location.getColumn());
        record.setDirectiveName(directive.getName());
        record.setDirectiveArgumentName(argumentName);
        record.setValueSdl(AstPrinter.printAstCompact(value));
        sink.add(record);
    }
}
