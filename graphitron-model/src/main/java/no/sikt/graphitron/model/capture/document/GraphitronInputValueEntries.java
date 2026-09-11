package no.sikt.graphitron.model.capture.document;

import graphql.language.Directive;
import no.sikt.graphitron.model.grammar.QualifiedNameGrammar;
import org.jooq.DSLContext;
import org.jooq.Rows;
import org.jooq.Table;

import java.time.LocalDateTime;
import java.util.List;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_INPUT_VALUE_BINDING_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_INPUT_VALUE_CONDITION_CONTEXT_ARG_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_INPUT_VALUE_CONDITION_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_INPUT_VALUE_DEPRECATED_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_INPUT_VALUE_NODE_ID_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_INPUT_VALUE_REFERENCE_FOR_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_INPUT_VALUE_REFERENCE_CONDITION_STEP_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_INPUT_VALUE_REFERENCE_FOR_CONDITION_STEP_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_INPUT_VALUE_REFERENCE_FOR_KEY_STEP_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_INPUT_VALUE_REFERENCE_FOR_TABLE_STEP_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_INPUT_VALUE_REFERENCE_KEY_STEP_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_INPUT_VALUE_REFERENCE_TABLE_STEP_ENTRY;
import static no.sikt.graphitron.model.capture.document.GraphitronEntries.applied;
import static no.sikt.graphitron.model.capture.document.GraphitronEntries.conditioned;
import static no.sikt.graphitron.model.capture.document.GraphitronEntries.tabled;
import static no.sikt.graphitron.model.capture.document.GraphitronEntries.keyed;
import static no.sikt.graphitron.model.capture.document.GraphitronEntries.bool;
import static no.sikt.graphitron.model.capture.document.GraphitronEntries.inside;
import static no.sikt.graphitron.model.capture.document.GraphitronEntries.naming;
import static no.sikt.graphitron.model.capture.document.GraphitronEntries.Step;
import static no.sikt.graphitron.model.capture.document.GraphitronEntries.steps;
import static no.sikt.graphitron.model.capture.document.GraphitronEntries.string;
import static no.sikt.graphitron.model.capture.document.GraphitronEntries.wrote;
import static no.sikt.graphitron.model.capture.document.GraphitronEntries.writtenIn;
import static org.jooq.impl.DSL.excluded;
import static org.jooq.impl.DSL.val;

/**
 * The input-value site's half of the decode: what a directive written on an input value meant.
 *
 * <p>Keyed by the application's own position, which is
 * {@code graphql_ast_input_value_directive_entry}'s key, so a row here is the decode of exactly one
 * row there. Which input value the directive was written on, and in which file, is one join away
 * rather than a column.
 *
 * <p>One site over three parents, because the parser has one node here: a field's argument, an
 * input object's field and a directive definition's own argument are all an input value. Which of
 * the three a row sits under is a position on the AST row rather than a key, a parent in one of
 * three relations being sound to reference and unspellable as a constraint. The third of them
 * carries no graphitron directive in any corpus we read, though it is not empty: the bundled
 * vocabulary deprecates {@code @asConnection(connectionName:)}.
 *
 * <p>Eight directive names reach this site and five get a relation. {@code @lookupKey},
 * {@code @orderBy} and {@code @asFacet} declare no argument at all, so a row for any of them would
 * carry its key and nothing else, which is what the applied-directive row already says.
 * {@code @reference} has only its path, so its steps get a relation and the application does not.
 * Neither repeatable directive carries an ordinal, each application standing at its own at sign.
 *
 * @see GraphitronEntries for what every site's decode holds in common
 */
final class GraphitronInputValueEntries {

    private GraphitronInputValueEntries() {}

    /**
     * Makes {@code source}'s input-value rows under {@code graph} be what {@code document}'s
     * applications now say.
     */
    static void write(DSLContext dsl, String graph, String source,
                      List<SdlEntries.Nested<Directive>> applications, LocalDateTime touchedAt) {
        bindings(dsl, graph, touchedAt, wrote(applied(applications, "field"), "name"));

        var conditions = naming(applied(applications, "condition"), "condition");
        conditions(dsl, graph, touchedAt, conditions);
        conditionContextArguments(dsl, graph, touchedAt, conditions);

        var referenceSteps = steps(applied(applications, "reference"));
        referenceKeySteps(dsl, graph, touchedAt, keyed(referenceSteps));
        referenceTableSteps(dsl, graph, touchedAt, tabled(referenceSteps));
        referenceConditionSteps(dsl, graph, touchedAt, conditioned(referenceSteps));

        var referenceFor = wrote(applied(applications, "referenceFor"), "type");
        referencesFor(dsl, graph, touchedAt, referenceFor);
        var referenceForSteps = steps(referenceFor);
        referenceForKeySteps(dsl, graph, touchedAt, keyed(referenceForSteps));
        referenceForTableSteps(dsl, graph, touchedAt, tabled(referenceForSteps));
        referenceForConditionSteps(dsl, graph, touchedAt, conditioned(referenceForSteps));

        nodeIds(dsl, graph, touchedAt, wrote(applied(applications, "nodeId"), "typeName"));
        deprecations(dsl, graph, touchedAt, applied(applications, "deprecated"));
        GraphitronEntries.sweep(dsl, graph, source, touchedAt, TABLES_TO_SWEEP);
    }

    /**
     * What the sweep deletes from, and the only thing that reads it. Listed rather than found by
     * prefix: a relation added above and not here would keep its stale rows silently.
     */
    private static final List<Table<?>> TABLES_TO_SWEEP = List.of(
        GRAPHITRON_AST_INPUT_VALUE_BINDING_ENTRY, GRAPHITRON_AST_INPUT_VALUE_CONDITION_ENTRY,
        GRAPHITRON_AST_INPUT_VALUE_DEPRECATED_ENTRY,
        GRAPHITRON_AST_INPUT_VALUE_CONDITION_CONTEXT_ARG_ENTRY,
        GRAPHITRON_AST_INPUT_VALUE_REFERENCE_KEY_STEP_ENTRY,
        GRAPHITRON_AST_INPUT_VALUE_REFERENCE_TABLE_STEP_ENTRY,
        GRAPHITRON_AST_INPUT_VALUE_REFERENCE_CONDITION_STEP_ENTRY,
        GRAPHITRON_AST_INPUT_VALUE_REFERENCE_FOR_ENTRY,
        GRAPHITRON_AST_INPUT_VALUE_REFERENCE_FOR_KEY_STEP_ENTRY,
        GRAPHITRON_AST_INPUT_VALUE_REFERENCE_FOR_TABLE_STEP_ENTRY,
        GRAPHITRON_AST_INPUT_VALUE_REFERENCE_FOR_CONDITION_STEP_ENTRY,
        GRAPHITRON_AST_INPUT_VALUE_NODE_ID_ENTRY);

    private static void bindings(DSLContext dsl, String graph, LocalDateTime touchedAt,
                                 List<Directive> applications) {
        var t = GRAPHITRON_AST_INPUT_VALUE_BINDING_ENTRY;
        var rows = applications.stream().collect(Rows.toRowList(
            application -> val(graph, t.GRAPH_NAME),
            application -> SdlEntries.sourceName(application),
            application -> SdlEntries.sourceLine(application),
            application -> SdlEntries.sourceColumn(application),
            application -> val(touchedAt, t.TOUCHED_AT),
            application -> val(string(application, "name"), t.NAME_REF)));
        if (rows.isEmpty()) {
            return;
        }
        dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN,
                t.TOUCHED_AT, t.NAME_REF)
            .valuesOfRows(rows)
            .onDuplicateKeyUpdate()
            .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
            .set(t.NAME_REF, excluded(t.NAME_REF))
            .execute();
    }

    private static void conditions(DSLContext dsl, String graph, LocalDateTime touchedAt,
                                   List<Directive> applications) {
        var t = GRAPHITRON_AST_INPUT_VALUE_CONDITION_ENTRY;
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
        if (rows.isEmpty()) {
            return;
        }
        dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN,
                t.TOUCHED_AT, t.CLASS_NAME, t.METHOD, t.ARGMAPPING, t.OVERRIDE)
            .valuesOfRows(rows)
            .onDuplicateKeyUpdate()
            .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
            .set(t.CLASS_NAME, excluded(t.CLASS_NAME))
            .set(t.METHOD, excluded(t.METHOD))
            .set(t.ARGMAPPING, excluded(t.ARGMAPPING))
            .set(t.OVERRIDE, excluded(t.OVERRIDE))
            .execute();
    }

    private static void conditionContextArguments(DSLContext dsl, String graph,
                                                  LocalDateTime touchedAt,
                                                  List<Directive> applications) {
        var t = GRAPHITRON_AST_INPUT_VALUE_CONDITION_CONTEXT_ARG_ENTRY;
        var rows = writtenIn(applications, "contextArguments").stream().collect(Rows.toRowList(
            written -> val(graph, t.GRAPH_NAME),
            written -> SdlEntries.sourceName(written.application()),
            written -> SdlEntries.sourceLine(written.application()),
            written -> SdlEntries.sourceColumn(written.application()),
            written -> val(written.position(), t.POSITION),
            written -> val(touchedAt, t.TOUCHED_AT),
            written -> val(written.value(), t.NAME)));
        if (rows.isEmpty()) {
            return;
        }
        dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN, t.POSITION,
                t.TOUCHED_AT, t.NAME)
            .valuesOfRows(rows)
            .onDuplicateKeyUpdate()
            .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
            .set(t.NAME, excluded(t.NAME))
            .execute();
    }

    private static void referencesFor(DSLContext dsl, String graph, LocalDateTime touchedAt,
                                      List<Directive> applications) {
        var t = GRAPHITRON_AST_INPUT_VALUE_REFERENCE_FOR_ENTRY;
        var rows = applications.stream().collect(Rows.toRowList(
            application -> val(graph, t.GRAPH_NAME),
            application -> SdlEntries.sourceName(application),
            application -> SdlEntries.sourceLine(application),
            application -> SdlEntries.sourceColumn(application),
            application -> val(touchedAt, t.TOUCHED_AT),
            application -> val(string(application, "type"), t.PARTICIPANT_TYPE_REF)));
        if (rows.isEmpty()) {
            return;
        }
        dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN,
                t.TOUCHED_AT, t.PARTICIPANT_TYPE_REF)
            .valuesOfRows(rows)
            .onDuplicateKeyUpdate()
            .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
            .set(t.PARTICIPANT_TYPE_REF, excluded(t.PARTICIPANT_TYPE_REF))
            .execute();
    }


    // ------------------------------------------- one step, as many rows as the author made claims

    private static void referenceKeySteps(DSLContext dsl, String graph, LocalDateTime touchedAt,
                           List<Step> steps) {
        var t = GRAPHITRON_AST_INPUT_VALUE_REFERENCE_KEY_STEP_ENTRY;
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
        if (rows.isEmpty()) {
            return;
        }
        dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN, t.POSITION,
                t.TOUCHED_AT, t.KEY_REF, t.KEY_REF_NAMESPACE_PART, t.KEY_REF_NAME_PART)
            .valuesOfRows(rows)
            .onDuplicateKeyUpdate()
            .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
            .set(t.KEY_REF, excluded(t.KEY_REF))
            .set(t.KEY_REF_NAMESPACE_PART, excluded(t.KEY_REF_NAMESPACE_PART))
            .set(t.KEY_REF_NAME_PART, excluded(t.KEY_REF_NAME_PART))
            .execute();
    }


    private static void referenceTableSteps(DSLContext dsl, String graph, LocalDateTime touchedAt,
                           List<Step> steps) {
        var t = GRAPHITRON_AST_INPUT_VALUE_REFERENCE_TABLE_STEP_ENTRY;
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
        if (rows.isEmpty()) {
            return;
        }
        dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN, t.POSITION,
                t.TOUCHED_AT, t.TABLE_REF, t.TABLE_REF_NAMESPACE_PART, t.TABLE_REF_NAME_PART)
            .valuesOfRows(rows)
            .onDuplicateKeyUpdate()
            .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
            .set(t.TABLE_REF, excluded(t.TABLE_REF))
            .set(t.TABLE_REF_NAMESPACE_PART, excluded(t.TABLE_REF_NAMESPACE_PART))
            .set(t.TABLE_REF_NAME_PART, excluded(t.TABLE_REF_NAME_PART))
            .execute();
    }


    private static void referenceConditionSteps(DSLContext dsl, String graph, LocalDateTime touchedAt,
                           List<Step> steps) {
        var t = GRAPHITRON_AST_INPUT_VALUE_REFERENCE_CONDITION_STEP_ENTRY;
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
        if (rows.isEmpty()) {
            return;
        }
        dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN, t.POSITION,
                t.TOUCHED_AT, t.CLASS_NAME, t.METHOD, t.ARGMAPPING)
            .valuesOfRows(rows)
            .onDuplicateKeyUpdate()
            .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
            .set(t.CLASS_NAME, excluded(t.CLASS_NAME))
            .set(t.METHOD, excluded(t.METHOD))
            .set(t.ARGMAPPING, excluded(t.ARGMAPPING))
            .execute();
    }


    private static void referenceForKeySteps(DSLContext dsl, String graph, LocalDateTime touchedAt,
                           List<Step> steps) {
        var t = GRAPHITRON_AST_INPUT_VALUE_REFERENCE_FOR_KEY_STEP_ENTRY;
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
        if (rows.isEmpty()) {
            return;
        }
        dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN, t.POSITION,
                t.TOUCHED_AT, t.KEY_REF, t.KEY_REF_NAMESPACE_PART, t.KEY_REF_NAME_PART)
            .valuesOfRows(rows)
            .onDuplicateKeyUpdate()
            .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
            .set(t.KEY_REF, excluded(t.KEY_REF))
            .set(t.KEY_REF_NAMESPACE_PART, excluded(t.KEY_REF_NAMESPACE_PART))
            .set(t.KEY_REF_NAME_PART, excluded(t.KEY_REF_NAME_PART))
            .execute();
    }


    private static void referenceForTableSteps(DSLContext dsl, String graph, LocalDateTime touchedAt,
                           List<Step> steps) {
        var t = GRAPHITRON_AST_INPUT_VALUE_REFERENCE_FOR_TABLE_STEP_ENTRY;
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
        if (rows.isEmpty()) {
            return;
        }
        dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN, t.POSITION,
                t.TOUCHED_AT, t.TABLE_REF, t.TABLE_REF_NAMESPACE_PART, t.TABLE_REF_NAME_PART)
            .valuesOfRows(rows)
            .onDuplicateKeyUpdate()
            .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
            .set(t.TABLE_REF, excluded(t.TABLE_REF))
            .set(t.TABLE_REF_NAMESPACE_PART, excluded(t.TABLE_REF_NAMESPACE_PART))
            .set(t.TABLE_REF_NAME_PART, excluded(t.TABLE_REF_NAME_PART))
            .execute();
    }


    private static void referenceForConditionSteps(DSLContext dsl, String graph, LocalDateTime touchedAt,
                           List<Step> steps) {
        var t = GRAPHITRON_AST_INPUT_VALUE_REFERENCE_FOR_CONDITION_STEP_ENTRY;
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
        if (rows.isEmpty()) {
            return;
        }
        dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN, t.POSITION,
                t.TOUCHED_AT, t.CLASS_NAME, t.METHOD, t.ARGMAPPING)
            .valuesOfRows(rows)
            .onDuplicateKeyUpdate()
            .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
            .set(t.CLASS_NAME, excluded(t.CLASS_NAME))
            .set(t.METHOD, excluded(t.METHOD))
            .set(t.ARGMAPPING, excluded(t.ARGMAPPING))
            .execute();
    }

    /**
     * What a {@code @deprecated} application on an input value says.
     *
     * <p>Not graphitron's directive, and decoded here for the reason federation's {@code @key} is:
     * graphitron gives it a meaning the specification does not, unifying it with a docstring
     * convention GraphQL has no room for, so a consumer asking whether something is deprecated
     * should not have to know which of the two marked it.
     *
     * <p>Every input value, not only a directive definition's argument. Which of the three parents
     * the application sits under is the anchor's question, and an input field's own deprecation is
     * a fact a reader wants at its own coordinate anyway.
     */
    private static void deprecations(DSLContext dsl, String graph, LocalDateTime touchedAt,
                                     List<Directive> applications) {
        var t = GRAPHITRON_AST_INPUT_VALUE_DEPRECATED_ENTRY;
        var rows = applications.stream().collect(Rows.toRowList(
            application -> val(graph, t.GRAPH_NAME),
            application -> SdlEntries.sourceName(application),
            application -> SdlEntries.sourceLine(application),
            application -> SdlEntries.sourceColumn(application),
            application -> val(touchedAt, t.TOUCHED_AT),
            application -> val(string(application, "reason"), t.REASON)));
        if (rows.isEmpty()) {
            return;
        }
        dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN,
                t.TOUCHED_AT, t.REASON)
            .valuesOfRows(rows)
            .onDuplicateKeyUpdate()
            .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
            .set(t.REASON, excluded(t.REASON))
            .execute();
    }

    private static void nodeIds(DSLContext dsl, String graph, LocalDateTime touchedAt,
                                List<Directive> applications) {
        var t = GRAPHITRON_AST_INPUT_VALUE_NODE_ID_ENTRY;
        var rows = applications.stream().collect(Rows.toRowList(
            application -> val(graph, t.GRAPH_NAME),
            application -> SdlEntries.sourceName(application),
            application -> SdlEntries.sourceLine(application),
            application -> SdlEntries.sourceColumn(application),
            application -> val(touchedAt, t.TOUCHED_AT),
            application -> val(string(application, "typeName"), t.NODE_TYPE_REF)));
        if (rows.isEmpty()) {
            return;
        }
        dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN,
                t.TOUCHED_AT, t.NODE_TYPE_REF)
            .valuesOfRows(rows)
            .onDuplicateKeyUpdate()
            .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
            .set(t.NODE_TYPE_REF, excluded(t.NODE_TYPE_REF))
            .execute();
    }
}
