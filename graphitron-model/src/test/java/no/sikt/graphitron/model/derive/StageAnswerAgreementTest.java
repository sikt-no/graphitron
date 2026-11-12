package no.sikt.graphitron.model.derive;

import no.sikt.graphitron.model.test.FactGraphs;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.select;
import static org.jooq.impl.DSL.table;

/**
 * Every stage-written table holds exactly the rows the view stating its rule computes, on every
 * store the fact documents' captures filled.
 *
 * <p>The oracle a conversion rests on. A stage moves who evaluates a rule and when, and its whole
 * safety argument is that it moves nothing else; {@code EXCEPT} in both directions between the
 * table and the rule view is that argument as a query. It is a standing assertion rather than a
 * one-commit check because the rule survives the conversion: as long as both relations exist the
 * comparison is runnable, which is the property that keeping the rule a stored view buys and
 * moving its text into the stage would spend.
 *
 * <p>Both directions, because they fail differently and each on its own is half an oracle. Rows in
 * the table and not in the rule are rows a previous capture left behind, which is what an appending
 * stage or a mis-scoped {@code DELETE} produces; rows in the rule and not in the table are rows the
 * stage did not write, which is what a predicate in the wrong place produces.
 *
 * <p>Over the whole population of fact documents ({@link FactGraphs}), since the agreement is an
 * invariant of any captured graph: every scenario's final reading is compared, a stepped scenario's
 * included, so a row an earlier step left behind is seen, the configured-tag reading among them,
 * which {@code scenarios/configured-tag} states. The non-vacuity cases say the population did its job:
 * a comparison between two empty relations passes while asserting nothing, and one over a single
 * arm passes while asserting a fraction of the rule.
 */
public class StageAnswerAgreementTest {

    @RegisterExtension
    static final FactGraphs.Reader FACTS = FactGraphs.reader();


    /** One stage: the table it writes and the view stating the rule it inserts from. */
    public record Stage(String target, String ruleView) {}

    /**
     * The defect table several stages write, each from a rule view of its own, beside the walk,
     * which writes its defects as it goes and has no view. Each rule's rows are held, and every held
     * row of a code one of the rules' stages owns ({@code graphitron_defect_type.owner_stage}) is
     * one some rule computes. Not each rule against its owner's codes alone: the graphitron rule
     * also states the route codes the walk's stage owns, at the declaration of a field whose need no
     * claim covers.
     */
    public static final String DEFECTS = "graphitron_entry_defect";

    /**
     * The defect rule views and the stages that run them, read from the writer's own list so a stage
     * taking codes of its own is compared the day it lands.
     */
    private static final Map<String, String> DEFECT_RULES = EntryDefects.RULES.stream()
        .collect(java.util.stream.Collectors.toMap(
            rule -> rule.view().getName().toLowerCase(java.util.Locale.ROOT), EntryDefects.Rule::stage));

    /**
     * The stage-written tables and their rules, in stage order. Public because the scoping half of
     * the same equality, across two graphs in one store, is checked in the generator's module.
     */
    public static final List<Stage> STAGES = List.of(
        new Stage("code_graph_class", "code_graph_class_rule"),
        new Stage("graphitron_type_backing", "graphitron_type_backing_rule"),
        new Stage("graphitron_configured_tag", "graphitron_configured_tag_rule"),
        new Stage("graphitron_minted_coinage", "graphitron_minted_coinage_rule"),
        new Stage("graphitron_carrier_directive", "graphitron_carrier_directive_rule"),
        new Stage("graphitron_connection_carrier", "graphitron_connection_carrier_rule"),
        new Stage("graphitron_type_member", "graphitron_type_member_rule"),
        new Stage("graphitron_scalar_class", "graphitron_scalar_class_rule"),
        new Stage("graphitron_occurrence_input_type", "graphitron_occurrence_input_type_rule"),
        new Stage("graphitron_edge", "graphitron_edge_rule"),
        new Stage("graphitron_connection", "graphitron_connection_rule"),
        new Stage("graphitron_field_position", "graphitron_field_position_rule"),
        new Stage("graphitron_field_target", "graphitron_field_target_rule"),
        new Stage("graphitron_path_element_key", "graphitron_path_element_key_rule"),
        new Stage("graphitron_path_element_primary_key", "graphitron_path_element_primary_key_rule"),
        new Stage("graphitron_path_element_table", "graphitron_path_element_table_rule"),
        new Stage("graphitron_path_element_method", "graphitron_path_element_method_rule"),
        new Stage("graphitron_occurrence_list_element", "graphitron_occurrence_list_element_rule"),
        new Stage("graphitron_occurrence_overridden", "graphitron_occurrence_overridden_rule"),
        new Stage("graphitron_occurrence_list_element_defect", "graphitron_occurrence_list_element_defect_rule"),
        new Stage(DEFECTS, "graphitron_entity_key_entry_defect_rule"),
        new Stage(DEFECTS, "graphitron_occurrence_build_entry_defect_rule"),
        new Stage(DEFECTS, "graphitron_occurrence_depth_entry_defect_rule"),
        new Stage(DEFECTS, "graphitron_outbox_entry_defect_rule"),
        new Stage(DEFECTS, "graphitron_entry_defect_rule"));

    @Test
    @DisplayName("every stage-written table holds exactly its rule's rows, both directions")
    void everyStageAgreesWithTheRuleItInsertsFrom() {
        var disagreements = new ArrayList<String>();
        eachReading((graphs, dsl) -> {
            for (Stage stage : STAGES) {
                if (stage.target().equals(DEFECTS)) {
                    continue;
                }
                for (var graph : graphs) {
                    difference(dsl, graph, stage, true).forEach(row ->
                        disagreements.add(stage.target() + " holds a row " + stage.ruleView()
                            + " does not compute (" + graph + "): " + row));
                }
                for (var graph : graphs) {
                    difference(dsl, graph, stage, false).forEach(row ->
                        disagreements.add(stage.ruleView() + " computes a row " + stage.target()
                            + " does not hold (" + graph + "): " + row));
                }
            }
            for (var rule : DEFECT_RULES.keySet()) {
                for (var graph : graphs) {
                    dsl.selectFrom(defectRule(dsl, graph, rule).except(defectTable(dsl, graph)))
                        .fetch().forEach(row -> disagreements.add(rule + " computes a row " + DEFECTS
                            + " does not hold (" + graph + "): " + row));
                }
            }
            for (var graph : graphs) {
                var computed = DEFECT_RULES.keySet().stream()
                    .<org.jooq.Select<org.jooq.Record>>map(rule -> defectRule(dsl, graph, rule))
                    .reduce((left, right) -> left.union(right)).orElseThrow();
                dsl.selectFrom(defectTable(dsl, graph)
                        .and(field(name("CODE"), String.class).in(
                            select(field(name("CODE"), String.class))
                                .from(table(name("GRAPHITRON_DEFECT_TYPE")))
                                .where(field(name("OWNER_STAGE"), String.class).in(DEFECT_RULES.values()))))
                        .except(computed))
                    .fetch().forEach(row -> disagreements.add(DEFECTS + " holds a row of a code the"
                        + " rules' stages own that no rule computes (" + graph + "): " + row));
            }
        });
        assertThat(disagreements)
            .as("a table's row its rule does not compute is a previous capture's row the stage did"
                + " not clear, or one written twice; a rule's row its table lacks is one the stage"
                + " did not write")
            .isEmpty();
    }

    /**
     * Every stage's table holds rows somewhere, so no agreement above is between two empty
     * relations. The floor every conversion
     * owes and the one a new stage cannot land without meeting: a stage no document reaches wants
     * a document that reaches it.
     */
    @Test
    @DisplayName("every stage-written table holds rows")
    void noStageIsComparedOverAnEmptyRelation() {
        var populated = new HashSet<String>();
        eachReading((graphs, dsl) -> {
            for (var stage : STAGES) {
                for (var graph : graphs) {
                    if (populated.contains(stage.ruleView())) {
                        break;
                    }
                    if (dsl.fetchExists(table(name(stage.ruleView().toUpperCase())), inGraph(graph))) {
                        populated.add(stage.ruleView());
                    }
                }
            }
        });
        assertThat(STAGES.stream().map(Stage::ruleView).filter(t -> !populated.contains(t)).toList())
            .as("stage rules no fact document reaches; add a document that reaches one")
            .isEmpty();
    }

    /**
     * Runs {@code body} over each store of the population with the graphs it holds, every document
     * captured first. A comparison runs per graph, as a stage does: a rule view evaluated over a
     * whole store at once holds every graph's intermediate rows on the heap. Graphs are the inner
     * loop so one statement's text repeats with only the graph bound, and the session's plan cache
     * keeps it planned.
     */
    private void eachReading(java.util.function.BiConsumer<Set<String>, DSLContext> body) {
        FACTS.warm(FACTS.paths());
        FACTS.eachStore((dsl, graphs) -> {
            body.accept(graphs, dsl);
            return null;
        });
    }

    /**
     * The rows the stage's table holds and its rule does not compute ({@code tableSide}), or the
     * reverse, compared on the rule's own columns. A table marking and sweeping carries the
     * reading's instant beside what the rule states, and the instant is the writer's, not the
     * rule's, so it is no part of whether the two agree.
     */
    private static List<String> difference(DSLContext dsl, String graph, Stage stage, boolean tableSide) {
        var columns = columns(dsl, stage.ruleView());
        var held = select(columns).from(table(name(stage.target().toUpperCase()))).where(inGraph(graph));
        var computed = select(columns).from(table(name(stage.ruleView().toUpperCase())))
            .where(inGraph(graph));
        return (tableSide ? dsl.selectFrom(held.except(computed)) : dsl.selectFrom(computed.except(held)))
            .fetch().stream()
            .map(Object::toString)
            .toList();
    }

    private static org.jooq.SelectConditionStep<org.jooq.Record> defectRule(DSLContext dsl, String graph,
                                                                            String rule) {
        return select(columns(dsl, rule)).from(table(name(rule.toUpperCase()))).where(inGraph(graph));
    }

    private static org.jooq.SelectConditionStep<org.jooq.Record> defectTable(DSLContext dsl, String graph) {
        return select(columns(dsl, "graphitron_entry_defect_rule"))
            .from(table(name(DEFECTS.toUpperCase())))
            .where(inGraph(graph));
    }

    private static org.jooq.Condition inGraph(String graph) {
        return field(name("GRAPH_NAME"), String.class).eq(graph);
    }

    /** The rule's own columns, in its order: what a comparison with its table is over. */
    private static final Map<String, List<org.jooq.Field<Object>>> COLUMNS =
        new java.util.concurrent.ConcurrentHashMap<>();

    private static List<org.jooq.Field<Object>> columns(DSLContext dsl, String rule) {
        return COLUMNS.computeIfAbsent(rule, ignored -> dsl.select(field(name("COLUMN_NAME"), String.class))
            .from(table(name("INFORMATION_SCHEMA", "COLUMNS")))
            .where(field(name("TABLE_NAME"), String.class).eq(rule.toUpperCase()))
            .orderBy(field(name("ORDINAL_POSITION")))
            .fetch(0, String.class).stream()
            .map(column -> field(name(column)))
            .toList());
    }
}
