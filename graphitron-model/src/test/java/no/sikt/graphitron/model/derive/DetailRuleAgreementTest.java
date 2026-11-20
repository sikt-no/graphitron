package no.sikt.graphitron.model.derive;

import no.sikt.graphitron.model.test.FactGraphs;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Select;
import org.jooq.Table;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_ARGMAPPING_LANDING_ENTRY_DEFECT_PAIR_RULE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ARGMAPPING_LANDING_ENTRY_DEFECT_RULE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ARGMAPPING_PAIR_ENTRY_DEFECT_PAIR_RULE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ARGMAPPING_PAIR_ENTRY_DEFECT_RULE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_CALL_CONTEXT_KEY_ENTRY_DEFECT_PAIR_RULE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_CALL_CONTEXT_KEY_ENTRY_DEFECT_RULE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ENTRY_DEFECT_KEY_COLUMN_RULE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ENTRY_DEFECT_MEMBER_RULE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_BINDING_ENTRY_DEFECT_RULE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_BUILD_ENTRY_DEFECT_MEMBER_RULE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_BUILD_ENTRY_DEFECT_RULE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OUTBOX_ENTRY_DEFECT_PAIR_RULE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OUTBOX_ENTRY_DEFECT_RULE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_TABLE_TYPE_ENTRY_DEFECT_RULE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.select;

/**
 * Each detail rule and the stage rule raising the defects it details give one answer, on every graph
 * the fact documents capture.
 *
 * <p>A stage states its detail logic twice: in a detail rule, read by the writer of the detail rows,
 * and again in a CTE of the rule raising the defect. The defect is written first, since a detail row
 * hangs off it by foreign key, and no view may read a view, so the raising rule cannot read the
 * detail rule and copies it instead. Nothing in the schema holds the two equal; this test does. A
 * detail rule yielding a row the stage does not raise has its row dropped by the writer, which writes
 * only under a defect; a stage raising a defect its detail rule has no row for leaves the defect
 * without details.
 *
 * <p>Compared on the defect's key: the graph, the written position and the code. A detail row
 * carries more, an entry's position or a member, which is what makes several rows detail one defect.
 */
public class DetailRuleAgreementTest {

    @RegisterExtension
    static final FactGraphs.Reader FACTS = FactGraphs.reader();

    /**
     * A detail rule and the stage rule copying it: each one's defect key, graph, file, line, column
     * and code in that order, and the stage rule's rows the copy raises.
     */
    record Copy(Table<?> detail, List<Field<?>> detailKey, Table<?> stage, List<Field<?>> stageKey,
                Condition copied) {}

    static final List<Copy> COPIES = List.of(
        new Copy(GRAPHITRON_ARGMAPPING_PAIR_ENTRY_DEFECT_PAIR_RULE,
            List.of(GRAPHITRON_ARGMAPPING_PAIR_ENTRY_DEFECT_PAIR_RULE.GRAPH_NAME,
                GRAPHITRON_ARGMAPPING_PAIR_ENTRY_DEFECT_PAIR_RULE.SOURCE_NAME,
                GRAPHITRON_ARGMAPPING_PAIR_ENTRY_DEFECT_PAIR_RULE.SOURCE_LINE,
                GRAPHITRON_ARGMAPPING_PAIR_ENTRY_DEFECT_PAIR_RULE.SOURCE_COLUMN,
                GRAPHITRON_ARGMAPPING_PAIR_ENTRY_DEFECT_PAIR_RULE.CODE),
            GRAPHITRON_ARGMAPPING_PAIR_ENTRY_DEFECT_RULE,
            List.of(GRAPHITRON_ARGMAPPING_PAIR_ENTRY_DEFECT_RULE.GRAPH_NAME,
                GRAPHITRON_ARGMAPPING_PAIR_ENTRY_DEFECT_RULE.SOURCE_NAME,
                GRAPHITRON_ARGMAPPING_PAIR_ENTRY_DEFECT_RULE.SOURCE_LINE,
                GRAPHITRON_ARGMAPPING_PAIR_ENTRY_DEFECT_RULE.SOURCE_COLUMN,
                GRAPHITRON_ARGMAPPING_PAIR_ENTRY_DEFECT_RULE.CODE),
            GRAPHITRON_ARGMAPPING_PAIR_ENTRY_DEFECT_RULE.CODE.in("SESSION_BINDING_WITHOUT_IDENTITY",
                "SESSION_HANDLE_UNNAMED", "SESSION_MEMBER_UNKNOWN", "SESSION_MEMBER_AMBIGUOUS",
                "SESSION_BINDING_TYPE_MISMATCH", "CONTEXT_SITE_UNSUPPORTED", "CONTEXT_KEY_MALFORMED",
                "SCOPE_MEMBER_UNKNOWN", "ARGMAPPING_PARAMETER_UNKNOWN")),
        new Copy(GRAPHITRON_CALL_CONTEXT_KEY_ENTRY_DEFECT_PAIR_RULE,
            List.of(GRAPHITRON_CALL_CONTEXT_KEY_ENTRY_DEFECT_PAIR_RULE.GRAPH_NAME,
                GRAPHITRON_CALL_CONTEXT_KEY_ENTRY_DEFECT_PAIR_RULE.SOURCE_NAME,
                GRAPHITRON_CALL_CONTEXT_KEY_ENTRY_DEFECT_PAIR_RULE.SOURCE_LINE,
                GRAPHITRON_CALL_CONTEXT_KEY_ENTRY_DEFECT_PAIR_RULE.SOURCE_COLUMN,
                GRAPHITRON_CALL_CONTEXT_KEY_ENTRY_DEFECT_PAIR_RULE.CODE),
            GRAPHITRON_CALL_CONTEXT_KEY_ENTRY_DEFECT_RULE,
            List.of(GRAPHITRON_CALL_CONTEXT_KEY_ENTRY_DEFECT_RULE.GRAPH_NAME,
                GRAPHITRON_CALL_CONTEXT_KEY_ENTRY_DEFECT_RULE.SOURCE_NAME,
                GRAPHITRON_CALL_CONTEXT_KEY_ENTRY_DEFECT_RULE.SOURCE_LINE,
                GRAPHITRON_CALL_CONTEXT_KEY_ENTRY_DEFECT_RULE.SOURCE_COLUMN,
                GRAPHITRON_CALL_CONTEXT_KEY_ENTRY_DEFECT_RULE.CODE),
            // The stage raises the codes at a contextArguments element too, with a detail; the
            // mapping string's row carries none.
            GRAPHITRON_CALL_CONTEXT_KEY_ENTRY_DEFECT_RULE.CODE.in("CONTEXT_KEY_DUPLICATE",
                    "CONTEXT_KEY_TYPE_DISAGREES")
                .and(GRAPHITRON_CALL_CONTEXT_KEY_ENTRY_DEFECT_RULE.DETAIL.isNull())),
        new Copy(GRAPHITRON_ARGMAPPING_LANDING_ENTRY_DEFECT_PAIR_RULE,
            List.of(GRAPHITRON_ARGMAPPING_LANDING_ENTRY_DEFECT_PAIR_RULE.GRAPH_NAME,
                GRAPHITRON_ARGMAPPING_LANDING_ENTRY_DEFECT_PAIR_RULE.SOURCE_NAME,
                GRAPHITRON_ARGMAPPING_LANDING_ENTRY_DEFECT_PAIR_RULE.SOURCE_LINE,
                GRAPHITRON_ARGMAPPING_LANDING_ENTRY_DEFECT_PAIR_RULE.SOURCE_COLUMN,
                GRAPHITRON_ARGMAPPING_LANDING_ENTRY_DEFECT_PAIR_RULE.CODE),
            GRAPHITRON_ARGMAPPING_LANDING_ENTRY_DEFECT_RULE,
            List.of(GRAPHITRON_ARGMAPPING_LANDING_ENTRY_DEFECT_RULE.GRAPH_NAME,
                GRAPHITRON_ARGMAPPING_LANDING_ENTRY_DEFECT_RULE.SOURCE_NAME,
                GRAPHITRON_ARGMAPPING_LANDING_ENTRY_DEFECT_RULE.SOURCE_LINE,
                GRAPHITRON_ARGMAPPING_LANDING_ENTRY_DEFECT_RULE.SOURCE_COLUMN,
                GRAPHITRON_ARGMAPPING_LANDING_ENTRY_DEFECT_RULE.CODE),
            GRAPHITRON_ARGMAPPING_LANDING_ENTRY_DEFECT_RULE.CODE.in("ARGMAPPING_PATH_UNRESOLVED",
                "ARGMAPPING_PATH_PAST_CYCLE")),
        new Copy(GRAPHITRON_OUTBOX_ENTRY_DEFECT_PAIR_RULE,
            List.of(GRAPHITRON_OUTBOX_ENTRY_DEFECT_PAIR_RULE.GRAPH_NAME,
                GRAPHITRON_OUTBOX_ENTRY_DEFECT_PAIR_RULE.SOURCE_NAME,
                GRAPHITRON_OUTBOX_ENTRY_DEFECT_PAIR_RULE.SOURCE_LINE,
                GRAPHITRON_OUTBOX_ENTRY_DEFECT_PAIR_RULE.SOURCE_COLUMN,
                GRAPHITRON_OUTBOX_ENTRY_DEFECT_PAIR_RULE.CODE),
            GRAPHITRON_OUTBOX_ENTRY_DEFECT_RULE,
            List.of(GRAPHITRON_OUTBOX_ENTRY_DEFECT_RULE.GRAPH_NAME,
                GRAPHITRON_OUTBOX_ENTRY_DEFECT_RULE.SOURCE_NAME,
                GRAPHITRON_OUTBOX_ENTRY_DEFECT_RULE.SOURCE_LINE,
                GRAPHITRON_OUTBOX_ENTRY_DEFECT_RULE.SOURCE_COLUMN,
                GRAPHITRON_OUTBOX_ENTRY_DEFECT_RULE.CODE),
            GRAPHITRON_OUTBOX_ENTRY_DEFECT_RULE.CODE.eq("ARGMAPPING_SOURCE_MISFIT")),
        new Copy(GRAPHITRON_ENTRY_DEFECT_MEMBER_RULE,
            List.of(GRAPHITRON_ENTRY_DEFECT_MEMBER_RULE.GRAPH_NAME,
                GRAPHITRON_ENTRY_DEFECT_MEMBER_RULE.SOURCE_NAME,
                GRAPHITRON_ENTRY_DEFECT_MEMBER_RULE.SOURCE_LINE,
                GRAPHITRON_ENTRY_DEFECT_MEMBER_RULE.SOURCE_COLUMN,
                GRAPHITRON_ENTRY_DEFECT_MEMBER_RULE.CODE),
            GRAPHITRON_TABLE_TYPE_ENTRY_DEFECT_RULE,
            List.of(GRAPHITRON_TABLE_TYPE_ENTRY_DEFECT_RULE.GRAPH_NAME,
                GRAPHITRON_TABLE_TYPE_ENTRY_DEFECT_RULE.SOURCE_NAME,
                GRAPHITRON_TABLE_TYPE_ENTRY_DEFECT_RULE.SOURCE_LINE,
                GRAPHITRON_TABLE_TYPE_ENTRY_DEFECT_RULE.SOURCE_COLUMN,
                GRAPHITRON_TABLE_TYPE_ENTRY_DEFECT_RULE.CODE),
            GRAPHITRON_TABLE_TYPE_ENTRY_DEFECT_RULE.CODE.in("DISCRIMINATOR_MISSING",
                "TABLE_INTERFACE_PARTICIPANT_UNBOUND")),
        new Copy(GRAPHITRON_ENTRY_DEFECT_KEY_COLUMN_RULE,
            List.of(GRAPHITRON_ENTRY_DEFECT_KEY_COLUMN_RULE.GRAPH_NAME,
                GRAPHITRON_ENTRY_DEFECT_KEY_COLUMN_RULE.SOURCE_NAME,
                GRAPHITRON_ENTRY_DEFECT_KEY_COLUMN_RULE.SOURCE_LINE,
                GRAPHITRON_ENTRY_DEFECT_KEY_COLUMN_RULE.SOURCE_COLUMN,
                GRAPHITRON_ENTRY_DEFECT_KEY_COLUMN_RULE.CODE),
            GRAPHITRON_OCCURRENCE_BINDING_ENTRY_DEFECT_RULE,
            List.of(GRAPHITRON_OCCURRENCE_BINDING_ENTRY_DEFECT_RULE.GRAPH_NAME,
                GRAPHITRON_OCCURRENCE_BINDING_ENTRY_DEFECT_RULE.SOURCE_NAME,
                GRAPHITRON_OCCURRENCE_BINDING_ENTRY_DEFECT_RULE.SOURCE_LINE,
                GRAPHITRON_OCCURRENCE_BINDING_ENTRY_DEFECT_RULE.SOURCE_COLUMN,
                GRAPHITRON_OCCURRENCE_BINDING_ENTRY_DEFECT_RULE.CODE),
            GRAPHITRON_OCCURRENCE_BINDING_ENTRY_DEFECT_RULE.CODE.eq("ARGMAPPING_KEY_COLUMN_UNRESOLVED")),
        new Copy(GRAPHITRON_OCCURRENCE_BUILD_ENTRY_DEFECT_MEMBER_RULE,
            List.of(GRAPHITRON_OCCURRENCE_BUILD_ENTRY_DEFECT_MEMBER_RULE.GRAPH_NAME,
                GRAPHITRON_OCCURRENCE_BUILD_ENTRY_DEFECT_MEMBER_RULE.SOURCE_NAME,
                GRAPHITRON_OCCURRENCE_BUILD_ENTRY_DEFECT_MEMBER_RULE.SOURCE_LINE,
                GRAPHITRON_OCCURRENCE_BUILD_ENTRY_DEFECT_MEMBER_RULE.SOURCE_COLUMN,
                GRAPHITRON_OCCURRENCE_BUILD_ENTRY_DEFECT_MEMBER_RULE.CODE),
            GRAPHITRON_OCCURRENCE_BUILD_ENTRY_DEFECT_RULE,
            List.of(GRAPHITRON_OCCURRENCE_BUILD_ENTRY_DEFECT_RULE.GRAPH_NAME,
                GRAPHITRON_OCCURRENCE_BUILD_ENTRY_DEFECT_RULE.SOURCE_NAME,
                GRAPHITRON_OCCURRENCE_BUILD_ENTRY_DEFECT_RULE.SOURCE_LINE,
                GRAPHITRON_OCCURRENCE_BUILD_ENTRY_DEFECT_RULE.SOURCE_COLUMN,
                GRAPHITRON_OCCURRENCE_BUILD_ENTRY_DEFECT_RULE.CODE),
            GRAPHITRON_OCCURRENCE_BUILD_ENTRY_DEFECT_RULE.CODE.in("NODE_ID_MEMBER_NOT_NODE_TYPE",
                "NODE_ID_SLOT_NOT_SUPERTYPE_OF_MEMBER")));

    @Test
    @DisplayName("every detail rule details exactly the defects the stage rule copying it raises")
    void everyDetailRuleAgreesWithTheStageRuleCopyingIt() {
        var disagreements = new ArrayList<String>();
        eachReading((graphs, dsl) -> {
            for (var copy : COPIES) {
                for (var graph : graphs) {
                    dsl.fetch(detailed(copy, graph).except(raised(copy, graph))).forEach(row ->
                        disagreements.add(copy.detail().getName() + " details a defect "
                            + copy.stage().getName() + " does not raise (" + graph + "): " + row));
                }
                for (var graph : graphs) {
                    dsl.fetch(raised(copy, graph).except(detailed(copy, graph))).forEach(row ->
                        disagreements.add(copy.stage().getName() + " raises a defect "
                            + copy.detail().getName() + " does not detail (" + graph + "): " + row));
                }
            }
        });
        assertThat(disagreements)
            .as("a detail rule and the CTE copying it into the stage rule have drifted; a detail of no"
                + " raised defect is dropped by its writer, a defect with no detail row names none")
            .isEmpty();
    }

    /**
     * Every detail rule yields rows somewhere, so no agreement above is between two empty relations:
     * a copy no document reaches wants a document that reaches it.
     */
    @Test
    @DisplayName("every detail rule yields rows")
    void noDetailRuleIsComparedOverAnEmptyRelation() {
        var populated = new HashSet<String>();
        eachReading((graphs, dsl) -> {
            for (var copy : COPIES) {
                for (var graph : graphs) {
                    if (populated.contains(copy.detail().getName())) {
                        break;
                    }
                    if (dsl.fetchExists(copy.detail(), inGraph(graph))) {
                        populated.add(copy.detail().getName());
                    }
                }
            }
        });
        assertThat(COPIES.stream().map(copy -> copy.detail().getName())
                .filter(detail -> !populated.contains(detail)).toList())
            .as("detail rules no fact document reaches; add a document that reaches one")
            .isEmpty();
    }

    /** The defect keys the detail rule's rows hang off in {@code graph}. */
    private static Select<Record> detailed(Copy copy, String graph) {
        return select(copy.detailKey()).from(copy.detail()).where(inGraph(graph));
    }

    /** The defect keys the stage rule raises in {@code graph} from its copy of the detail rule. */
    private static Select<Record> raised(Copy copy, String graph) {
        return select(copy.stageKey()).from(copy.stage()).where(inGraph(graph)).and(copy.copied());
    }

    private static Condition inGraph(String graph) {
        return field(name("GRAPH_NAME"), String.class).eq(graph);
    }

    /** Runs {@code body} over each store of the population with the graphs it holds, all captured first. */
    private void eachReading(java.util.function.BiConsumer<java.util.Set<String>, DSLContext> body) {
        FACTS.warm(FACTS.paths());
        FACTS.eachStore((dsl, graphs) -> {
            body.accept(graphs, dsl);
            return null;
        });
    }
}
