package no.sikt.graphitron.model.capture.document;

import graphql.language.Directive;
import no.sikt.graphitron.model.grammar.QualifiedNameGrammar;
import no.sikt.graphitron.model.sink.BindBatch;
import org.jooq.DSLContext;
import org.jooq.Rows;
import org.jooq.Table;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_REFERENCE_CONDITION_STEP_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_REFERENCE_ELEMENT_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_REFERENCE_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_REFERENCE_FOR_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_REFERENCE_KEY_STEP_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_REFERENCE_TABLE_STEP_ENTRY;
import static no.sikt.graphitron.model.capture.document.GraphitronAstEntries.applied;
import static no.sikt.graphitron.model.capture.document.GraphitronAstEntries.conditioned;
import static no.sikt.graphitron.model.capture.document.GraphitronAstEntries.keyed;
import static no.sikt.graphitron.model.capture.document.GraphitronAstEntries.listedIn;
import static no.sikt.graphitron.model.capture.document.GraphitronAstEntries.steps;
import static no.sikt.graphitron.model.capture.document.GraphitronAstEntries.string;
import static no.sikt.graphitron.model.capture.document.GraphitronAstEntries.tabled;
import static no.sikt.graphitron.model.capture.document.GraphitronAstEntries.wrote;
import static org.jooq.impl.DSL.excluded;
import static org.jooq.impl.DSL.val;

/**
 * The writer of the path family: every element of every {@code path} a document writes, and the
 * participant each {@code @referenceFor} names, whichever site the directive was written at.
 *
 * <p>Not a site's writer. {@code @reference} and {@code @referenceFor} both carry
 * {@code path: [ReferenceElement!]!}, and a path is one grammar wherever it is written, so each
 * application gets one key-only decode, each element of its path one row at its index whatever it
 * says, and what an element names one row per thing it names, as subtypes of the element. Which
 * directive and which site an application sits under are the applied-directive row's facts.
 * {@code @referenceFor}'s {@code type} is the one argument the two directives do not share, so it
 * adds a row to the application's decode.
 *
 * <p>A segment is a key, a table or a method call, and an element naming several is a row in each
 * at one position, so every relation stays total. A method-call segment is written the way method
 * calls are written elsewhere, class, method and argMapping as the author typed them, and its own
 * relation is what says the method is a join at this position: a {@code @condition} names a method
 * too, but a different kind of one, and the two change apart.
 */
final class GraphitronReferencePathEntries {

    private GraphitronReferencePathEntries() {}

    /**
     * Makes {@code source}'s path rows under {@code graph} be what {@code applications} write, then
     * sweeps what this reading did not touch.
     */
    static void write(DSLContext dsl, String graph, String source,
                      List<GraphQLAstEntries.Nested<Directive>> applications,
                      LocalDateTime touchedAt) {
        var carryingPaths = new ArrayList<Directive>(applied(applications, "reference"));
        carryingPaths.addAll(applied(applications, "referenceFor"));
        references(dsl, graph, touchedAt, carryingPaths);
        elements(dsl, graph, touchedAt, listedIn(carryingPaths, "path"));
        var referenceFor = wrote(applied(applications, "referenceFor"), "type");
        var steps = steps(carryingPaths);
        tableSteps(dsl, graph, touchedAt, tabled(steps));
        keySteps(dsl, graph, touchedAt, keyed(steps));
        conditionSteps(dsl, graph, touchedAt, conditioned(steps));
        referencesFor(dsl, graph, touchedAt, referenceFor);
        GraphitronAstEntries.sweep(dsl, graph, source, touchedAt, TABLES_TO_SWEEP);
    }

    /**
     * What the sweep deletes from, and the only thing that reads it. Listed rather than found by
     * prefix: a relation added above and not here would keep its stale rows silently.
     */
    private static final List<Table<?>> TABLES_TO_SWEEP = List.of(
        GRAPHITRON_AST_REFERENCE_TABLE_STEP_ENTRY,
        GRAPHITRON_AST_REFERENCE_KEY_STEP_ENTRY,
        GRAPHITRON_AST_REFERENCE_CONDITION_STEP_ENTRY,
        GRAPHITRON_AST_REFERENCE_FOR_ENTRY,
        GRAPHITRON_AST_REFERENCE_ELEMENT_ENTRY,
        GRAPHITRON_AST_REFERENCE_ENTRY);

    private static void references(DSLContext dsl, String graph, LocalDateTime touchedAt,
                                   List<Directive> applications) {
        var t = GRAPHITRON_AST_REFERENCE_ENTRY;
        var rows = applications.stream().collect(Rows.toRowList(
            application -> val(graph, t.GRAPH_NAME),
            application -> GraphQLAstEntries.sourceName(application),
            application -> GraphQLAstEntries.sourceLine(application),
            application -> GraphQLAstEntries.sourceColumn(application),
            application -> val(touchedAt, t.TOUCHED_AT)));
        BindBatch.execute(dsl, rows, markers ->
            dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN,
                    t.TOUCHED_AT)
                .values(markers)
                .onDuplicateKeyUpdate()
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT)));
    }

    private static void elements(DSLContext dsl, String graph, LocalDateTime touchedAt,
                                 List<GraphitronAstEntries.Listed> elements) {
        var t = GRAPHITRON_AST_REFERENCE_ELEMENT_ENTRY;
        var rows = elements.stream().collect(Rows.toRowList(
            element -> val(graph, t.GRAPH_NAME),
            element -> GraphQLAstEntries.sourceName(element.node()),
            element -> GraphQLAstEntries.sourceLine(element.node()),
            element -> GraphQLAstEntries.sourceColumn(element.node()),
            element -> val(touchedAt, t.TOUCHED_AT),
            element -> GraphQLAstEntries.sourceLine(element.application()),
            element -> GraphQLAstEntries.sourceColumn(element.application()),
            element -> val(element.position(), t.POSITION)));
        BindBatch.execute(dsl, rows, markers ->
            dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN,
                    t.TOUCHED_AT, t.APPLICATION_LINE, t.APPLICATION_COLUMN, t.POSITION)
                .values(markers)
                .onDuplicateKeyUpdate()
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
                .set(t.APPLICATION_LINE, excluded(t.APPLICATION_LINE))
                .set(t.APPLICATION_COLUMN, excluded(t.APPLICATION_COLUMN))
                .set(t.POSITION, excluded(t.POSITION)));
    }

    private static void tableSteps(DSLContext dsl, String graph, LocalDateTime touchedAt,
                                   List<GraphitronAstEntries.Step> steps) {
        var t = GRAPHITRON_AST_REFERENCE_TABLE_STEP_ENTRY;
        var rows = steps.stream().collect(Rows.toRowList(
            step -> val(graph, t.GRAPH_NAME),
            step -> GraphQLAstEntries.sourceName(step.node()),
            step -> GraphQLAstEntries.sourceLine(step.node()),
            step -> GraphQLAstEntries.sourceColumn(step.node()),
            step -> val(touchedAt, t.TOUCHED_AT),
            step -> val(step.tableRef(), t.TABLE_REF),
            step -> val(QualifiedNameGrammar.namespacePart(step.tableRef()), t.TABLE_REF_NAMESPACE_PART),
            step -> val(QualifiedNameGrammar.namePart(step.tableRef()), t.TABLE_REF_NAME_PART)));
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

    private static void keySteps(DSLContext dsl, String graph, LocalDateTime touchedAt,
                                 List<GraphitronAstEntries.Step> steps) {
        var t = GRAPHITRON_AST_REFERENCE_KEY_STEP_ENTRY;
        var rows = steps.stream().collect(Rows.toRowList(
            step -> val(graph, t.GRAPH_NAME),
            step -> GraphQLAstEntries.sourceName(step.node()),
            step -> GraphQLAstEntries.sourceLine(step.node()),
            step -> GraphQLAstEntries.sourceColumn(step.node()),
            step -> val(touchedAt, t.TOUCHED_AT),
            step -> val(step.keyRef(), t.KEY_REF),
            step -> val(QualifiedNameGrammar.namespacePart(step.keyRef()), t.KEY_REF_NAMESPACE_PART),
            step -> val(QualifiedNameGrammar.namePart(step.keyRef()), t.KEY_REF_NAME_PART)));
        BindBatch.execute(dsl, rows, markers ->
            dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN,
                    t.TOUCHED_AT, t.KEY_REF, t.KEY_REF_NAMESPACE_PART, t.KEY_REF_NAME_PART)
                .values(markers)
                .onDuplicateKeyUpdate()
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
                .set(t.KEY_REF, excluded(t.KEY_REF))
                .set(t.KEY_REF_NAMESPACE_PART, excluded(t.KEY_REF_NAMESPACE_PART))
                .set(t.KEY_REF_NAME_PART, excluded(t.KEY_REF_NAME_PART)));
    }

    private static void conditionSteps(DSLContext dsl, String graph, LocalDateTime touchedAt,
                                       List<GraphitronAstEntries.Step> steps) {
        var t = GRAPHITRON_AST_REFERENCE_CONDITION_STEP_ENTRY;
        var rows = steps.stream().collect(Rows.toRowList(
            step -> val(graph, t.GRAPH_NAME),
            step -> GraphQLAstEntries.sourceName(step.node()),
            step -> GraphQLAstEntries.sourceLine(step.node()),
            step -> GraphQLAstEntries.sourceColumn(step.node()),
            step -> val(touchedAt, t.TOUCHED_AT),
            step -> val(step.className(), t.CLASS_NAME),
            step -> val(step.method(), t.METHOD),
            step -> val(step.argMapping(), t.ARGMAPPING)));
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

    private static void referencesFor(DSLContext dsl, String graph, LocalDateTime touchedAt,
                                      List<Directive> applications) {
        var t = GRAPHITRON_AST_REFERENCE_FOR_ENTRY;
        var rows = applications.stream().collect(Rows.toRowList(
            application -> val(graph, t.GRAPH_NAME),
            application -> GraphQLAstEntries.sourceName(application),
            application -> GraphQLAstEntries.sourceLine(application),
            application -> GraphQLAstEntries.sourceColumn(application),
            application -> val(touchedAt, t.TOUCHED_AT),
            application -> val(string(application, "type"), t.PARTICIPANT_TYPE_REF)));
        BindBatch.execute(dsl, rows, markers ->
            dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN,
                    t.TOUCHED_AT, t.PARTICIPANT_TYPE_REF)
                .values(markers)
                .onDuplicateKeyUpdate()
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
                .set(t.PARTICIPANT_TYPE_REF, excluded(t.PARTICIPANT_TYPE_REF)));
    }
}
