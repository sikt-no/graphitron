package no.sikt.graphitron.model.capture.document;

import graphql.language.Directive;
import no.sikt.graphitron.model.grammar.QualifiedNameGrammar;
import no.sikt.graphitron.model.sink.RowChunks;
import org.jooq.DSLContext;
import org.jooq.Rows;
import org.jooq.Table;

import java.time.LocalDateTime;
import java.util.List;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_CONNECTION_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_DEFAULT_ORDER_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_DEFAULT_ORDER_FIELD_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_EXTERNAL_FIELD_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_FIELD_BINDING_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_FIELD_CONDITION_CONTEXT_ARG_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_FIELD_CONDITION_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_FIELD_NODE_ID_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_FIELD_REFERENCE_FOR_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_FIELD_REFERENCE_FOR_CONDITION_STEP_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_FIELD_REFERENCE_FOR_KEY_STEP_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_FIELD_REFERENCE_FOR_TABLE_STEP_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_FIELD_REFERENCE_CONDITION_STEP_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_FIELD_REFERENCE_KEY_STEP_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_FIELD_REFERENCE_TABLE_STEP_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_MUTATION_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_PIVOT_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_ROUTINE_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_SERVICE_CONTEXT_ARG_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_SERVICE_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_SOURCE_ROW_ENTRY;
import static no.sikt.graphitron.model.capture.document.GraphitronEntries.applied;
import static no.sikt.graphitron.model.capture.document.GraphitronEntries.conditioned;
import static no.sikt.graphitron.model.capture.document.GraphitronEntries.keyed;
import static no.sikt.graphitron.model.capture.document.GraphitronEntries.tabled;
import static no.sikt.graphitron.model.capture.document.GraphitronEntries.bool;
import static no.sikt.graphitron.model.capture.document.GraphitronEntries.elementsOf;
import static no.sikt.graphitron.model.capture.document.GraphitronEntries.inside;
import static no.sikt.graphitron.model.capture.document.GraphitronEntries.integer;
import static no.sikt.graphitron.model.capture.document.GraphitronEntries.naming;
import static no.sikt.graphitron.model.capture.document.GraphitronEntries.steps;
import static no.sikt.graphitron.model.capture.document.GraphitronEntries.string;
import static no.sikt.graphitron.model.capture.document.GraphitronEntries.stringOf;
import static no.sikt.graphitron.model.capture.document.GraphitronEntries.token;
import static no.sikt.graphitron.model.capture.document.GraphitronEntries.wrote;
import static no.sikt.graphitron.model.capture.document.GraphitronEntries.writtenIn;
import static org.jooq.impl.DSL.excluded;
import static org.jooq.impl.DSL.val;

/**
 * The field site's half of the decode: what a directive written on an output field meant.
 *
 * <p>Keyed by the application's own position, which is {@code graphql_ast_field_directive_entry}'s
 * key, so a row here is the decode of exactly one row there. Which field the directive was written
 * on, and in which file, is one join away rather than a column.
 *
 * <p>The site is the field definitions of objects and interfaces, and nothing else. An input
 * object's field is an input value to the parser, so a directive written on one is the input-value
 * site's, not this one's, even where the two sites share a directive name. That is a change from
 * how the walk this replaces routed them, and it follows from the key: an entry references the AST
 * row it decodes, and those rows are in different relations.
 *
 * <p>Sixteen directive names reach this site and twelve of them get a relation. {@code @splitQuery}
 * and {@code @tenantFanOut} declare no argument, and {@code @multitableReference} is rejected
 * before anything reads its one argument, so a row for any of the three would carry its key and
 * nothing else, which is what the applied-directive row already says. {@code @reference} has only
 * its path, so its steps get a relation and the application does not.
 *
 * @see GraphitronEntries for what every site's decode holds in common
 */
final class GraphitronFieldEntries {

    private GraphitronFieldEntries() {}

    /**
     * Makes {@code source}'s field-site rows under {@code graph} be what {@code document}'s
     * applications now say.
     */
    static void write(DSLContext dsl, String graph, String source,
                      List<SdlEntries.Nested<Directive>> applications, LocalDateTime touchedAt) {
        bindings(dsl, graph, touchedAt, wrote(applied(applications, "field"), "name"));

        var conditions = naming(applied(applications, "condition"), "condition");
        conditions(dsl, graph, touchedAt, conditions);
        conditionContextArguments(dsl, graph, touchedAt, conditions);

        var referenceSteps = steps(applied(applications, "reference"));
        referenceTableSteps(dsl, graph, touchedAt, tabled(referenceSteps));
        referenceKeySteps(dsl, graph, touchedAt, keyed(referenceSteps));
        referenceConditionSteps(dsl, graph, touchedAt, conditioned(referenceSteps));
        var referenceFor = wrote(applied(applications, "referenceFor"), "type");
        referencesFor(dsl, graph, touchedAt, referenceFor);
        var referenceForSteps = steps(referenceFor);
        referenceForTableSteps(dsl, graph, touchedAt, tabled(referenceForSteps));
        referenceForKeySteps(dsl, graph, touchedAt, keyed(referenceForSteps));
        referenceForConditionSteps(dsl, graph, touchedAt, conditioned(referenceForSteps));

        var services = naming(applied(applications, "service"), "service");
        services(dsl, graph, touchedAt, services);
        serviceContextArguments(dsl, graph, touchedAt, services);

        externalFields(dsl, graph, touchedAt, naming(applied(applications, "externalField"), "reference"));
        sourceRows(dsl, graph, touchedAt, wrote(applied(applications, "sourceRow"), "className"));
        connections(dsl, graph, touchedAt, applied(applications, "asConnection"));
        nodeIds(dsl, graph, touchedAt, wrote(applied(applications, "nodeId"), "typeName"));
        mutations(dsl, graph, touchedAt, token(applied(applications, "mutation"), "typeName"));
        pivots(dsl, graph, touchedAt, wrote(wrote(applied(applications, "pivot"), "on"), "value"));

        var defaultOrders = applied(applications, "defaultOrder");
        defaultOrders(dsl, graph, touchedAt, defaultOrders);
        defaultOrderFields(dsl, graph, touchedAt, defaultOrders);

        routines(dsl, graph, touchedAt, wrote(applied(applications, "routine"), "name"));
        GraphitronEntries.sweep(dsl, graph, source, touchedAt, TABLES_TO_SWEEP);
    }

    /**
     * What the sweep deletes from, and the only thing that reads it. Listed rather than found by
     * prefix: a relation added above and not here would keep its stale rows silently.
     */
    private static final List<Table<?>> TABLES_TO_SWEEP = List.of(
        GRAPHITRON_AST_FIELD_BINDING_ENTRY, GRAPHITRON_AST_FIELD_CONDITION_ENTRY,
        GRAPHITRON_AST_FIELD_CONDITION_CONTEXT_ARG_ENTRY,
        GRAPHITRON_AST_FIELD_REFERENCE_TABLE_STEP_ENTRY,
        GRAPHITRON_AST_FIELD_REFERENCE_KEY_STEP_ENTRY,
        GRAPHITRON_AST_FIELD_REFERENCE_CONDITION_STEP_ENTRY,
        GRAPHITRON_AST_FIELD_REFERENCE_FOR_ENTRY,
        GRAPHITRON_AST_FIELD_REFERENCE_FOR_TABLE_STEP_ENTRY,
        GRAPHITRON_AST_FIELD_REFERENCE_FOR_KEY_STEP_ENTRY,
        GRAPHITRON_AST_FIELD_REFERENCE_FOR_CONDITION_STEP_ENTRY,
        GRAPHITRON_AST_SERVICE_ENTRY,
        GRAPHITRON_AST_SERVICE_CONTEXT_ARG_ENTRY, GRAPHITRON_AST_EXTERNAL_FIELD_ENTRY,
        GRAPHITRON_AST_SOURCE_ROW_ENTRY, GRAPHITRON_AST_CONNECTION_ENTRY,
        GRAPHITRON_AST_FIELD_NODE_ID_ENTRY, GRAPHITRON_AST_MUTATION_ENTRY,
        GRAPHITRON_AST_PIVOT_ENTRY, GRAPHITRON_AST_DEFAULT_ORDER_ENTRY,
        GRAPHITRON_AST_DEFAULT_ORDER_FIELD_ENTRY, GRAPHITRON_AST_ROUTINE_ENTRY);

    private static void bindings(DSLContext dsl, String graph, LocalDateTime touchedAt,
                                 List<Directive> applications) {
        var t = GRAPHITRON_AST_FIELD_BINDING_ENTRY;
        var rows = applications.stream().collect(Rows.toRowList(
            application -> val(graph, t.GRAPH_NAME),
            application -> SdlEntries.sourceName(application),
            application -> SdlEntries.sourceLine(application),
            application -> SdlEntries.sourceColumn(application),
            application -> val(touchedAt, t.TOUCHED_AT),
            application -> val(string(application, "name"), t.NAME_REF)));
        RowChunks.execute(rows, chunk ->
            dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN,
                    t.TOUCHED_AT, t.NAME_REF)
                .valuesOfRows(chunk)
                .onDuplicateKeyUpdate()
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
                .set(t.NAME_REF, excluded(t.NAME_REF)));
    }

    private static void conditions(DSLContext dsl, String graph, LocalDateTime touchedAt,
                                   List<Directive> applications) {
        var t = GRAPHITRON_AST_FIELD_CONDITION_ENTRY;
        var rows = applications.stream().collect(Rows.toRowList(
            application -> val(graph, t.GRAPH_NAME),
            application -> SdlEntries.sourceName(application),
            application -> SdlEntries.sourceLine(application),
            application -> SdlEntries.sourceColumn(application),
            application -> val(touchedAt, t.TOUCHED_AT),
            application -> val(inside(application, "condition", "className"), t.CLASS_NAME),
            application -> val(inside(application, "condition", "method"), t.METHOD),
            application -> val(inside(application, "condition", "argMapping"), t.ARGMAPPING),
            application -> val(bool(application, "override"), t.OVERRIDE)));
        RowChunks.execute(rows, chunk ->
            dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN,
                    t.TOUCHED_AT, t.CLASS_NAME, t.METHOD, t.ARGMAPPING, t.OVERRIDE)
                .valuesOfRows(chunk)
                .onDuplicateKeyUpdate()
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
                .set(t.CLASS_NAME, excluded(t.CLASS_NAME))
                .set(t.METHOD, excluded(t.METHOD))
                .set(t.ARGMAPPING, excluded(t.ARGMAPPING))
                .set(t.OVERRIDE, excluded(t.OVERRIDE)));
    }

    private static void conditionContextArguments(DSLContext dsl, String graph,
                                                  LocalDateTime touchedAt,
                                                  List<Directive> applications) {
        var t = GRAPHITRON_AST_FIELD_CONDITION_CONTEXT_ARG_ENTRY;
        var rows = writtenIn(applications, "contextArguments").stream().collect(Rows.toRowList(
            written -> val(graph, t.GRAPH_NAME),
            written -> SdlEntries.sourceName(written.application()),
            written -> SdlEntries.sourceLine(written.application()),
            written -> SdlEntries.sourceColumn(written.application()),
            written -> val(written.position(), t.POSITION),
            written -> val(touchedAt, t.TOUCHED_AT),
            written -> val(written.value(), t.NAME)));
        RowChunks.execute(rows, chunk ->
            dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN, t.POSITION,
                    t.TOUCHED_AT, t.NAME)
                .valuesOfRows(chunk)
                .onDuplicateKeyUpdate()
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
                .set(t.NAME, excluded(t.NAME)));
    }

    private static void referencesFor(DSLContext dsl, String graph, LocalDateTime touchedAt,
                                      List<Directive> applications) {
        var t = GRAPHITRON_AST_FIELD_REFERENCE_FOR_ENTRY;
        var rows = applications.stream().collect(Rows.toRowList(
            application -> val(graph, t.GRAPH_NAME),
            application -> SdlEntries.sourceName(application),
            application -> SdlEntries.sourceLine(application),
            application -> SdlEntries.sourceColumn(application),
            application -> val(touchedAt, t.TOUCHED_AT),
            application -> val(string(application, "type"), t.PARTICIPANT_TYPE_REF)));
        RowChunks.execute(rows, chunk ->
            dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN,
                    t.TOUCHED_AT, t.PARTICIPANT_TYPE_REF)
                .valuesOfRows(chunk)
                .onDuplicateKeyUpdate()
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
                .set(t.PARTICIPANT_TYPE_REF, excluded(t.PARTICIPANT_TYPE_REF)));
    }

    private static void referenceTableSteps(DSLContext dsl, String graph, LocalDateTime touchedAt,
                                       List<GraphitronEntries.Step> steps) {
        var t = GRAPHITRON_AST_FIELD_REFERENCE_TABLE_STEP_ENTRY;
        var rows = steps.stream().collect(Rows.toRowList(
            step -> val(graph, t.GRAPH_NAME),
            step -> SdlEntries.sourceName(step.application()),
            step -> SdlEntries.sourceLine(step.application()),
            step -> SdlEntries.sourceColumn(step.application()),
            step -> val(step.position(), t.POSITION),
            step -> val(touchedAt, t.TOUCHED_AT),
            step -> val(step.tableRef(), t.TABLE_REF),
            step -> val(QualifiedNameGrammar.namespacePart(step.tableRef()), t.TABLE_REF_NAMESPACE_PART),
            step -> val(QualifiedNameGrammar.namePart(step.tableRef()), t.TABLE_REF_NAME_PART)));
        RowChunks.execute(rows, chunk ->
            dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN, t.POSITION,
                    t.TOUCHED_AT, t.TABLE_REF, t.TABLE_REF_NAMESPACE_PART, t.TABLE_REF_NAME_PART)
                .valuesOfRows(chunk)
                .onDuplicateKeyUpdate()
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
                .set(t.TABLE_REF, excluded(t.TABLE_REF))
                .set(t.TABLE_REF_NAMESPACE_PART, excluded(t.TABLE_REF_NAMESPACE_PART))
                .set(t.TABLE_REF_NAME_PART, excluded(t.TABLE_REF_NAME_PART)));
    }

    private static void referenceKeySteps(DSLContext dsl, String graph, LocalDateTime touchedAt,
                                       List<GraphitronEntries.Step> steps) {
        var t = GRAPHITRON_AST_FIELD_REFERENCE_KEY_STEP_ENTRY;
        var rows = steps.stream().collect(Rows.toRowList(
            step -> val(graph, t.GRAPH_NAME),
            step -> SdlEntries.sourceName(step.application()),
            step -> SdlEntries.sourceLine(step.application()),
            step -> SdlEntries.sourceColumn(step.application()),
            step -> val(step.position(), t.POSITION),
            step -> val(touchedAt, t.TOUCHED_AT),
            step -> val(step.keyRef(), t.KEY_REF),
            step -> val(QualifiedNameGrammar.namespacePart(step.keyRef()), t.KEY_REF_NAMESPACE_PART),
            step -> val(QualifiedNameGrammar.namePart(step.keyRef()), t.KEY_REF_NAME_PART)));
        RowChunks.execute(rows, chunk ->
            dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN, t.POSITION,
                    t.TOUCHED_AT, t.KEY_REF, t.KEY_REF_NAMESPACE_PART, t.KEY_REF_NAME_PART)
                .valuesOfRows(chunk)
                .onDuplicateKeyUpdate()
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
                .set(t.KEY_REF, excluded(t.KEY_REF))
                .set(t.KEY_REF_NAMESPACE_PART, excluded(t.KEY_REF_NAMESPACE_PART))
                .set(t.KEY_REF_NAME_PART, excluded(t.KEY_REF_NAME_PART)));
    }

    private static void referenceConditionSteps(DSLContext dsl, String graph, LocalDateTime touchedAt,
                                       List<GraphitronEntries.Step> steps) {
        var t = GRAPHITRON_AST_FIELD_REFERENCE_CONDITION_STEP_ENTRY;
        var rows = steps.stream().collect(Rows.toRowList(
            step -> val(graph, t.GRAPH_NAME),
            step -> SdlEntries.sourceName(step.application()),
            step -> SdlEntries.sourceLine(step.application()),
            step -> SdlEntries.sourceColumn(step.application()),
            step -> val(step.position(), t.POSITION),
            step -> val(touchedAt, t.TOUCHED_AT),
            step -> val(step.className(), t.CLASS_NAME),
            step -> val(step.method(), t.METHOD),
            step -> val(step.argMapping(), t.ARGMAPPING)));
        RowChunks.execute(rows, chunk ->
            dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN, t.POSITION,
                    t.TOUCHED_AT, t.CLASS_NAME, t.METHOD, t.ARGMAPPING)
                .valuesOfRows(chunk)
                .onDuplicateKeyUpdate()
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
                .set(t.CLASS_NAME, excluded(t.CLASS_NAME))
                .set(t.METHOD, excluded(t.METHOD))
                .set(t.ARGMAPPING, excluded(t.ARGMAPPING)));
    }

    private static void referenceForTableSteps(DSLContext dsl, String graph, LocalDateTime touchedAt,
                                       List<GraphitronEntries.Step> steps) {
        var t = GRAPHITRON_AST_FIELD_REFERENCE_FOR_TABLE_STEP_ENTRY;
        var rows = steps.stream().collect(Rows.toRowList(
            step -> val(graph, t.GRAPH_NAME),
            step -> SdlEntries.sourceName(step.application()),
            step -> SdlEntries.sourceLine(step.application()),
            step -> SdlEntries.sourceColumn(step.application()),
            step -> val(step.position(), t.POSITION),
            step -> val(touchedAt, t.TOUCHED_AT),
            step -> val(step.tableRef(), t.TABLE_REF),
            step -> val(QualifiedNameGrammar.namespacePart(step.tableRef()), t.TABLE_REF_NAMESPACE_PART),
            step -> val(QualifiedNameGrammar.namePart(step.tableRef()), t.TABLE_REF_NAME_PART)));
        RowChunks.execute(rows, chunk ->
            dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN, t.POSITION,
                    t.TOUCHED_AT, t.TABLE_REF, t.TABLE_REF_NAMESPACE_PART, t.TABLE_REF_NAME_PART)
                .valuesOfRows(chunk)
                .onDuplicateKeyUpdate()
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
                .set(t.TABLE_REF, excluded(t.TABLE_REF))
                .set(t.TABLE_REF_NAMESPACE_PART, excluded(t.TABLE_REF_NAMESPACE_PART))
                .set(t.TABLE_REF_NAME_PART, excluded(t.TABLE_REF_NAME_PART)));
    }

    private static void referenceForKeySteps(DSLContext dsl, String graph, LocalDateTime touchedAt,
                                       List<GraphitronEntries.Step> steps) {
        var t = GRAPHITRON_AST_FIELD_REFERENCE_FOR_KEY_STEP_ENTRY;
        var rows = steps.stream().collect(Rows.toRowList(
            step -> val(graph, t.GRAPH_NAME),
            step -> SdlEntries.sourceName(step.application()),
            step -> SdlEntries.sourceLine(step.application()),
            step -> SdlEntries.sourceColumn(step.application()),
            step -> val(step.position(), t.POSITION),
            step -> val(touchedAt, t.TOUCHED_AT),
            step -> val(step.keyRef(), t.KEY_REF),
            step -> val(QualifiedNameGrammar.namespacePart(step.keyRef()), t.KEY_REF_NAMESPACE_PART),
            step -> val(QualifiedNameGrammar.namePart(step.keyRef()), t.KEY_REF_NAME_PART)));
        RowChunks.execute(rows, chunk ->
            dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN, t.POSITION,
                    t.TOUCHED_AT, t.KEY_REF, t.KEY_REF_NAMESPACE_PART, t.KEY_REF_NAME_PART)
                .valuesOfRows(chunk)
                .onDuplicateKeyUpdate()
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
                .set(t.KEY_REF, excluded(t.KEY_REF))
                .set(t.KEY_REF_NAMESPACE_PART, excluded(t.KEY_REF_NAMESPACE_PART))
                .set(t.KEY_REF_NAME_PART, excluded(t.KEY_REF_NAME_PART)));
    }

    private static void referenceForConditionSteps(DSLContext dsl, String graph, LocalDateTime touchedAt,
                                       List<GraphitronEntries.Step> steps) {
        var t = GRAPHITRON_AST_FIELD_REFERENCE_FOR_CONDITION_STEP_ENTRY;
        var rows = steps.stream().collect(Rows.toRowList(
            step -> val(graph, t.GRAPH_NAME),
            step -> SdlEntries.sourceName(step.application()),
            step -> SdlEntries.sourceLine(step.application()),
            step -> SdlEntries.sourceColumn(step.application()),
            step -> val(step.position(), t.POSITION),
            step -> val(touchedAt, t.TOUCHED_AT),
            step -> val(step.className(), t.CLASS_NAME),
            step -> val(step.method(), t.METHOD),
            step -> val(step.argMapping(), t.ARGMAPPING)));
        RowChunks.execute(rows, chunk ->
            dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN, t.POSITION,
                    t.TOUCHED_AT, t.CLASS_NAME, t.METHOD, t.ARGMAPPING)
                .valuesOfRows(chunk)
                .onDuplicateKeyUpdate()
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
                .set(t.CLASS_NAME, excluded(t.CLASS_NAME))
                .set(t.METHOD, excluded(t.METHOD))
                .set(t.ARGMAPPING, excluded(t.ARGMAPPING)));
    }

    private static void services(DSLContext dsl, String graph, LocalDateTime touchedAt,
                                 List<Directive> applications) {
        var t = GRAPHITRON_AST_SERVICE_ENTRY;
        var rows = applications.stream().collect(Rows.toRowList(
            application -> val(graph, t.GRAPH_NAME),
            application -> SdlEntries.sourceName(application),
            application -> SdlEntries.sourceLine(application),
            application -> SdlEntries.sourceColumn(application),
            application -> val(touchedAt, t.TOUCHED_AT),
            application -> val(inside(application, "service", "className"), t.CLASS_NAME),
            application -> val(inside(application, "service", "method"), t.METHOD),
            application -> val(inside(application, "service", "argMapping"), t.ARGMAPPING)));
        RowChunks.execute(rows, chunk ->
            dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN,
                    t.TOUCHED_AT, t.CLASS_NAME, t.METHOD, t.ARGMAPPING)
                .valuesOfRows(chunk)
                .onDuplicateKeyUpdate()
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
                .set(t.CLASS_NAME, excluded(t.CLASS_NAME))
                .set(t.METHOD, excluded(t.METHOD))
                .set(t.ARGMAPPING, excluded(t.ARGMAPPING)));
    }

    private static void serviceContextArguments(DSLContext dsl, String graph,
                                                LocalDateTime touchedAt,
                                                List<Directive> applications) {
        var t = GRAPHITRON_AST_SERVICE_CONTEXT_ARG_ENTRY;
        var rows = writtenIn(applications, "contextArguments").stream().collect(Rows.toRowList(
            written -> val(graph, t.GRAPH_NAME),
            written -> SdlEntries.sourceName(written.application()),
            written -> SdlEntries.sourceLine(written.application()),
            written -> SdlEntries.sourceColumn(written.application()),
            written -> val(written.position(), t.POSITION),
            written -> val(touchedAt, t.TOUCHED_AT),
            written -> val(written.value(), t.NAME)));
        RowChunks.execute(rows, chunk ->
            dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN, t.POSITION,
                    t.TOUCHED_AT, t.NAME)
                .valuesOfRows(chunk)
                .onDuplicateKeyUpdate()
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
                .set(t.NAME, excluded(t.NAME)));
    }

    private static void externalFields(DSLContext dsl, String graph, LocalDateTime touchedAt,
                                       List<Directive> applications) {
        var t = GRAPHITRON_AST_EXTERNAL_FIELD_ENTRY;
        var rows = applications.stream().collect(Rows.toRowList(
            application -> val(graph, t.GRAPH_NAME),
            application -> SdlEntries.sourceName(application),
            application -> SdlEntries.sourceLine(application),
            application -> SdlEntries.sourceColumn(application),
            application -> val(touchedAt, t.TOUCHED_AT),
            application -> val(inside(application, "reference", "className"), t.CLASS_NAME),
            application -> val(inside(application, "reference", "method"), t.METHOD),
            application -> val(inside(application, "reference", "argMapping"), t.ARGMAPPING)));
        RowChunks.execute(rows, chunk ->
            dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN,
                    t.TOUCHED_AT, t.CLASS_NAME, t.METHOD, t.ARGMAPPING)
                .valuesOfRows(chunk)
                .onDuplicateKeyUpdate()
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
                .set(t.CLASS_NAME, excluded(t.CLASS_NAME))
                .set(t.METHOD, excluded(t.METHOD))
                .set(t.ARGMAPPING, excluded(t.ARGMAPPING)));
    }

    /** Two flat arguments, this being the one site that names a class and a method directly. */
    private static void sourceRows(DSLContext dsl, String graph, LocalDateTime touchedAt,
                                   List<Directive> applications) {
        var t = GRAPHITRON_AST_SOURCE_ROW_ENTRY;
        var rows = applications.stream().collect(Rows.toRowList(
            application -> val(graph, t.GRAPH_NAME),
            application -> SdlEntries.sourceName(application),
            application -> SdlEntries.sourceLine(application),
            application -> SdlEntries.sourceColumn(application),
            application -> val(touchedAt, t.TOUCHED_AT),
            application -> val(string(application, "className"), t.CLASS_NAME),
            application -> val(string(application, "method"), t.METHOD)));
        RowChunks.execute(rows, chunk ->
            dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN,
                    t.TOUCHED_AT, t.CLASS_NAME, t.METHOD)
                .valuesOfRows(chunk)
                .onDuplicateKeyUpdate()
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
                .set(t.CLASS_NAME, excluded(t.CLASS_NAME))
                .set(t.METHOD, excluded(t.METHOD)));
    }

    private static void connections(DSLContext dsl, String graph, LocalDateTime touchedAt,
                                    List<Directive> applications) {
        var t = GRAPHITRON_AST_CONNECTION_ENTRY;
        var rows = applications.stream().collect(Rows.toRowList(
            application -> val(graph, t.GRAPH_NAME),
            application -> SdlEntries.sourceName(application),
            application -> SdlEntries.sourceLine(application),
            application -> SdlEntries.sourceColumn(application),
            application -> val(touchedAt, t.TOUCHED_AT),
            application -> val(integer(application, "defaultFirstValue"), t.DEFAULT_FIRST_VALUE),
            application -> val(string(application, "connectionName"), t.CONNECTION_NAME)));
        RowChunks.execute(rows, chunk ->
            dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN,
                    t.TOUCHED_AT, t.DEFAULT_FIRST_VALUE, t.CONNECTION_NAME)
                .valuesOfRows(chunk)
                .onDuplicateKeyUpdate()
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
                .set(t.DEFAULT_FIRST_VALUE, excluded(t.DEFAULT_FIRST_VALUE))
                .set(t.CONNECTION_NAME, excluded(t.CONNECTION_NAME)));
    }

    private static void nodeIds(DSLContext dsl, String graph, LocalDateTime touchedAt,
                                List<Directive> applications) {
        var t = GRAPHITRON_AST_FIELD_NODE_ID_ENTRY;
        var rows = applications.stream().collect(Rows.toRowList(
            application -> val(graph, t.GRAPH_NAME),
            application -> SdlEntries.sourceName(application),
            application -> SdlEntries.sourceLine(application),
            application -> SdlEntries.sourceColumn(application),
            application -> val(touchedAt, t.TOUCHED_AT),
            application -> val(string(application, "typeName"), t.NODE_TYPE_REF)));
        RowChunks.execute(rows, chunk ->
            dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN,
                    t.TOUCHED_AT, t.NODE_TYPE_REF)
                .valuesOfRows(chunk)
                .onDuplicateKeyUpdate()
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
                .set(t.NODE_TYPE_REF, excluded(t.NODE_TYPE_REF)));
    }

    private static void mutations(DSLContext dsl, String graph, LocalDateTime touchedAt,
                                  List<Directive> applications) {
        var t = GRAPHITRON_AST_MUTATION_ENTRY;
        var rows = applications.stream().collect(Rows.toRowList(
            application -> val(graph, t.GRAPH_NAME),
            application -> SdlEntries.sourceName(application),
            application -> SdlEntries.sourceLine(application),
            application -> SdlEntries.sourceColumn(application),
            application -> val(touchedAt, t.TOUCHED_AT),
            application -> val(token(application, "typeName"), t.OPERATION),
            application -> val(bool(application, "multiRow"), t.MULTI_ROW),
            application -> val(string(application, "table"), t.TABLE_REF),
            application -> val(QualifiedNameGrammar.namespacePart(string(application, "table")),
                t.TABLE_REF_NAMESPACE_PART),
            application -> val(QualifiedNameGrammar.namePart(string(application, "table")),
                t.TABLE_REF_NAME_PART)));
        RowChunks.execute(rows, chunk ->
            dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN,
                    t.TOUCHED_AT, t.OPERATION, t.MULTI_ROW, t.TABLE_REF,
                    t.TABLE_REF_NAMESPACE_PART, t.TABLE_REF_NAME_PART)
                .valuesOfRows(chunk)
                .onDuplicateKeyUpdate()
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
                .set(t.OPERATION, excluded(t.OPERATION))
                .set(t.MULTI_ROW, excluded(t.MULTI_ROW))
                .set(t.TABLE_REF, excluded(t.TABLE_REF))
                .set(t.TABLE_REF_NAMESPACE_PART, excluded(t.TABLE_REF_NAMESPACE_PART))
                .set(t.TABLE_REF_NAME_PART, excluded(t.TABLE_REF_NAME_PART)));
    }

    private static void pivots(DSLContext dsl, String graph, LocalDateTime touchedAt,
                               List<Directive> applications) {
        var t = GRAPHITRON_AST_PIVOT_ENTRY;
        var rows = applications.stream().collect(Rows.toRowList(
            application -> val(graph, t.GRAPH_NAME),
            application -> SdlEntries.sourceName(application),
            application -> SdlEntries.sourceLine(application),
            application -> SdlEntries.sourceColumn(application),
            application -> val(touchedAt, t.TOUCHED_AT),
            application -> val(string(application, "on"), t.ON_COLUMN),
            application -> val(string(application, "value"), t.VALUE_COLUMN),
            application -> val(string(application, "vocabulary"), t.VOCABULARY_REF)));
        RowChunks.execute(rows, chunk ->
            dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN,
                    t.TOUCHED_AT, t.ON_COLUMN, t.VALUE_COLUMN, t.VOCABULARY_REF)
                .valuesOfRows(chunk)
                .onDuplicateKeyUpdate()
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
                .set(t.ON_COLUMN, excluded(t.ON_COLUMN))
                .set(t.VALUE_COLUMN, excluded(t.VALUE_COLUMN))
                .set(t.VOCABULARY_REF, excluded(t.VOCABULARY_REF)));
    }

    private static void defaultOrders(DSLContext dsl, String graph, LocalDateTime touchedAt,
                                      List<Directive> applications) {
        var t = GRAPHITRON_AST_DEFAULT_ORDER_ENTRY;
        var rows = applications.stream().collect(Rows.toRowList(
            application -> val(graph, t.GRAPH_NAME),
            application -> SdlEntries.sourceName(application),
            application -> SdlEntries.sourceLine(application),
            application -> SdlEntries.sourceColumn(application),
            application -> val(touchedAt, t.TOUCHED_AT),
            application -> val(string(application, "index"), t.INDEX_REF),
            application -> val(bool(application, "primaryKey"), t.PRIMARY_KEY),
            application -> val(token(application, "direction"), t.DIRECTION)));
        RowChunks.execute(rows, chunk ->
            dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN,
                    t.TOUCHED_AT, t.INDEX_REF, t.PRIMARY_KEY, t.DIRECTION)
                .valuesOfRows(chunk)
                .onDuplicateKeyUpdate()
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
                .set(t.INDEX_REF, excluded(t.INDEX_REF))
                .set(t.PRIMARY_KEY, excluded(t.PRIMARY_KEY))
                .set(t.DIRECTION, excluded(t.DIRECTION)));
    }

    private static void defaultOrderFields(DSLContext dsl, String graph, LocalDateTime touchedAt,
                                           List<Directive> applications) {
        var t = GRAPHITRON_AST_DEFAULT_ORDER_FIELD_ENTRY;
        var rows = elementsOf(applications, "fields").stream().collect(Rows.toRowList(
            element -> val(graph, t.GRAPH_NAME),
            element -> SdlEntries.sourceName(element.application()),
            element -> SdlEntries.sourceLine(element.application()),
            element -> SdlEntries.sourceColumn(element.application()),
            element -> val(element.position(), t.POSITION),
            element -> val(touchedAt, t.TOUCHED_AT),
            element -> val(stringOf(inside(element.value(), "name")), t.NAME_REF),
            element -> val(stringOf(inside(element.value(), "collate")), t.COLLATE),
            element -> val(GraphitronEntries.tokenOf(inside(element.value(), "direction")),
                t.DIRECTION)));
        RowChunks.execute(rows, chunk ->
            dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN, t.POSITION,
                    t.TOUCHED_AT, t.NAME_REF, t.COLLATE, t.DIRECTION)
                .valuesOfRows(chunk)
                .onDuplicateKeyUpdate()
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
                .set(t.NAME_REF, excluded(t.NAME_REF))
                .set(t.COLLATE, excluded(t.COLLATE))
                .set(t.DIRECTION, excluded(t.DIRECTION)));
    }

    private static void routines(DSLContext dsl, String graph, LocalDateTime touchedAt,
                                 List<Directive> applications) {
        var t = GRAPHITRON_AST_ROUTINE_ENTRY;
        var rows = applications.stream().collect(Rows.toRowList(
            application -> val(graph, t.GRAPH_NAME),
            application -> SdlEntries.sourceName(application),
            application -> SdlEntries.sourceLine(application),
            application -> SdlEntries.sourceColumn(application),
            application -> val(touchedAt, t.TOUCHED_AT),
            application -> val(string(application, "name"), t.ROUTINE_REF),
            application -> val(QualifiedNameGrammar.namespacePart(string(application, "name")),
                t.ROUTINE_REF_NAMESPACE_PART),
            application -> val(QualifiedNameGrammar.namePart(string(application, "name")),
                t.ROUTINE_REF_NAME_PART),
            application -> val(string(application, "argMapping"), t.ARGMAPPING),
            application -> val(string(application, "columnMapping"), t.COLUMN_MAPPING)));
        RowChunks.execute(rows, chunk ->
            dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN,
                    t.TOUCHED_AT, t.ROUTINE_REF, t.ROUTINE_REF_NAMESPACE_PART, t.ROUTINE_REF_NAME_PART,
                    t.ARGMAPPING, t.COLUMN_MAPPING)
                .valuesOfRows(chunk)
                .onDuplicateKeyUpdate()
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
                .set(t.ROUTINE_REF, excluded(t.ROUTINE_REF))
                .set(t.ROUTINE_REF_NAMESPACE_PART, excluded(t.ROUTINE_REF_NAMESPACE_PART))
                .set(t.ROUTINE_REF_NAME_PART, excluded(t.ROUTINE_REF_NAME_PART))
                .set(t.ARGMAPPING, excluded(t.ARGMAPPING))
                .set(t.COLUMN_MAPPING, excluded(t.COLUMN_MAPPING)));
    }
}
