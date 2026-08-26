package no.sikt.graphitron.model.intent;

import no.sikt.graphitron.model.test.SeededStore.OccurrenceStep;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static no.sikt.graphitron.model.Tables.INTENT_INPUT_OCCURRENCE_DESCENT_ORDER;
import static no.sikt.graphitron.model.test.SeededStore.seedArgument;
import static no.sikt.graphitron.model.test.SeededStore.seedDeclaredType;
import static no.sikt.graphitron.model.test.SeededStore.seedField;
import static no.sikt.graphitron.model.test.SeededStore.seedGraph;
import static no.sikt.graphitron.model.test.SeededStore.seedInputField;
import static no.sikt.graphitron.model.test.SeededStore.seedOccurrencePath;
import static no.sikt.graphitron.model.test.SeededStore.withSeededStore;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the descent-order relation returns: the order the flattener reaches a payload's occurrences
 * in, as a dense rank per argument. In the walker this order is nothing at all, being the order a
 * {@code for} loop over declared input fields happens to run in; as a relation it has to be stated,
 * because a row set has no order and two separate rules already need this one.
 *
 * <p>The order is a depth-first pre-order over declared input fields, and every case here separates
 * it from an order that would agree with it on a flat payload. Declaration order rather than name
 * order is one such: seeded field names run opposite to the ordinals, so a relation sorting on the
 * key would answer backwards. Pre-order rather than leaves-first is another, and the case that
 * states it puts a grouping between two plain siblings, where a container-last order and a
 * breadth-first order each produce a different answer than nesting-in-place does.
 *
 * <p>Depth is where the comparison can go wrong in a way a shallow payload cannot show, so two
 * cases go deep on purpose. Two occurrences under different groupings compare at the step they
 * first differ at and not at their leaves, which is stated by declaring the leaves in the order
 * that would reverse the answer if the leaves were what was compared. And two groupings declaring a
 * field of the same name is what tells a comparison on the outermost differing step from one on the
 * leaf name.
 *
 * <p>The rank is per argument and says nothing between arguments, so the cases that fix the
 * partition are about arguments rather than about rows: two arguments on one field each start at
 * zero, and a sibling graph's declaration order changes no answer here.
 */
class InputOccurrenceDescentOrderTest {

    private static final String GRAPH = "g";
    private static final String OTHER_GRAPH = "g2";

    // ===== Flat payloads =====

    /**
     * Siblings rank by the ordinal their type declares them at, not by their names. The three
     * fields are named so that name order is the exact reverse of declaration order, which is what
     * separates the rank from a sort on the relation's own key.
     */
    @Test
    void siblingsRankInTheirDeclarationOrderRatherThanByName() {
        withSeededStore(GRAPH, dsl -> {
            seedUseSite(dsl, GRAPH, "in", "Payload");
            seedInputField(dsl, GRAPH, "Payload", "c", "String", 0, false, false, null);
            seedInputField(dsl, GRAPH, "Payload", "b", "String", 1, false, false, null);
            seedInputField(dsl, GRAPH, "Payload", "a", "String", 2, false, false, null);
            seedOccurrencePath(dsl, GRAPH, "Mutation", "write", "in", "Payload",
                new OccurrenceStep("Payload", "c", "String"));
            seedOccurrencePath(dsl, GRAPH, "Mutation", "write", "in", "Payload",
                new OccurrenceStep("Payload", "b", "String"));
            seedOccurrencePath(dsl, GRAPH, "Mutation", "write", "in", "Payload",
                new OccurrenceStep("Payload", "a", "String"));

            assertThat(ranked(dsl)).containsExactly(
                "0 Mutation.write(in)",
                "1 Mutation.write(in)/c",
                "2 Mutation.write(in)/b",
                "3 Mutation.write(in)/a");
        });
    }

    /**
     * An argument whose input type declares nothing still has its own occurrence, and it ranks
     * zero. The degenerate payload is worth stating because the rank is a count of predecessors and
     * a count over an empty set is the one value a wrong join shape would still produce.
     */
    @Test
    void aPayloadRootWithNothingUnderItIsRankZero() {
        withSeededStore(GRAPH, dsl -> {
            seedUseSite(dsl, GRAPH, "in", "Payload");
            seedOccurrencePath(dsl, GRAPH, "Mutation", "write", "in", "Payload");

            assertThat(ranked(dsl)).containsExactly("0 Mutation.write(in)");
        });
    }

    // ===== Nesting =====

    /**
     * A grouping input is reached before the fields it groups, and its whole subtree lies between
     * it and its next sibling. The payload is a plain field, then a grouping of two, then another
     * plain field, so that a leaves-first order, a breadth-first order and a groupings-last order
     * each answer differently from the descent.
     */
    @Test
    void aGroupingsSubtreeLiesBetweenItAndItsNextSibling() {
        withSeededStore(GRAPH, dsl -> {
            seedUseSite(dsl, GRAPH, "in", "Payload");
            seedDeclaredType(dsl, GRAPH, "Group", "INPUT_OBJECT");
            seedInputField(dsl, GRAPH, "Payload", "before", "String", 0, false, false, null);
            seedInputField(dsl, GRAPH, "Payload", "group", "Group", 1, false, false, null);
            seedInputField(dsl, GRAPH, "Payload", "after", "String", 2, false, false, null);
            seedInputField(dsl, GRAPH, "Group", "inner", "String", 0, false, false, null);
            seedInputField(dsl, GRAPH, "Group", "alsoInner", "String", 1, false, false, null);
            seedOccurrencePath(dsl, GRAPH, "Mutation", "write", "in", "Payload",
                new OccurrenceStep("Payload", "before", "String"));
            seedOccurrencePath(dsl, GRAPH, "Mutation", "write", "in", "Payload",
                new OccurrenceStep("Payload", "group", "Group"),
                new OccurrenceStep("Group", "inner", "String"));
            seedOccurrencePath(dsl, GRAPH, "Mutation", "write", "in", "Payload",
                new OccurrenceStep("Payload", "group", "Group"),
                new OccurrenceStep("Group", "alsoInner", "String"));
            seedOccurrencePath(dsl, GRAPH, "Mutation", "write", "in", "Payload",
                new OccurrenceStep("Payload", "after", "String"));

            assertThat(ranked(dsl)).containsExactly(
                "0 Mutation.write(in)",
                "1 Mutation.write(in)/before",
                "2 Mutation.write(in)/group",
                "3 Mutation.write(in)/group/inner",
                "4 Mutation.write(in)/group/alsoInner",
                "5 Mutation.write(in)/after");
        });
    }

    /**
     * Two occurrences under different groupings compare at the step they first differ at and not at
     * their leaves. The leaves are declared in the order that would reverse the answer if a leaf
     * ordinal were what decided it: the earlier grouping's leaf is declared last in its type and the
     * later grouping's leaf first in its own.
     */
    @Test
    void twoOccurrencesCompareAtTheOutermostStepThatDiffers() {
        withSeededStore(GRAPH, dsl -> {
            seedUseSite(dsl, GRAPH, "in", "Payload");
            seedDeclaredType(dsl, GRAPH, "Early", "INPUT_OBJECT");
            seedDeclaredType(dsl, GRAPH, "Late", "INPUT_OBJECT");
            seedInputField(dsl, GRAPH, "Payload", "early", "Early", 0, false, false, null);
            seedInputField(dsl, GRAPH, "Payload", "late", "Late", 1, false, false, null);
            seedInputField(dsl, GRAPH, "Early", "filler", "String", 0, false, false, null);
            seedInputField(dsl, GRAPH, "Early", "lastDeclared", "String", 1, false, false, null);
            seedInputField(dsl, GRAPH, "Late", "firstDeclared", "String", 0, false, false, null);
            seedOccurrencePath(dsl, GRAPH, "Mutation", "write", "in", "Payload",
                new OccurrenceStep("Payload", "early", "Early"),
                new OccurrenceStep("Early", "lastDeclared", "String"));
            seedOccurrencePath(dsl, GRAPH, "Mutation", "write", "in", "Payload",
                new OccurrenceStep("Payload", "late", "Late"),
                new OccurrenceStep("Late", "firstDeclared", "String"));

            assertThat(ranked(dsl))
                .as("the leaf ordinals would put the late grouping's leaf first")
                .containsExactly(
                    "0 Mutation.write(in)",
                    "1 Mutation.write(in)/early",
                    "2 Mutation.write(in)/early/lastDeclared",
                    "3 Mutation.write(in)/late",
                    "4 Mutation.write(in)/late/firstDeclared");
        });
    }

    /**
     * Two groupings may each declare a field of the same name, and the two occurrences of that name
     * still rank by their groupings. A comparison reading the leaf name rather than the outermost
     * differing step has nothing to separate these two by.
     */
    @Test
    void twoGroupingsMayDeclareAFieldOfTheSameNameAndStillRankByTheirGroupings() {
        withSeededStore(GRAPH, dsl -> {
            seedUseSite(dsl, GRAPH, "in", "Payload");
            seedDeclaredType(dsl, GRAPH, "Second", "INPUT_OBJECT");
            seedDeclaredType(dsl, GRAPH, "First", "INPUT_OBJECT");
            seedInputField(dsl, GRAPH, "Payload", "second", "Second", 0, false, false, null);
            seedInputField(dsl, GRAPH, "Payload", "first", "First", 1, false, false, null);
            seedInputField(dsl, GRAPH, "Second", "id", "Int", 0, false, false, null);
            seedInputField(dsl, GRAPH, "First", "id", "Int", 0, false, false, null);
            seedOccurrencePath(dsl, GRAPH, "Mutation", "write", "in", "Payload",
                new OccurrenceStep("Payload", "first", "First"),
                new OccurrenceStep("First", "id", "Int"));
            seedOccurrencePath(dsl, GRAPH, "Mutation", "write", "in", "Payload",
                new OccurrenceStep("Payload", "second", "Second"),
                new OccurrenceStep("Second", "id", "Int"));

            assertThat(ranked(dsl)).containsExactly(
                "0 Mutation.write(in)",
                "1 Mutation.write(in)/second",
                "2 Mutation.write(in)/second/id",
                "3 Mutation.write(in)/first",
                "4 Mutation.write(in)/first/id");
        });
    }

    // ===== The partition =====

    /**
     * Two arguments of one field each rank from zero. The rank is a position within one descent and
     * the flattener descends an argument at a time, so a numbering shared across a field's arguments
     * would be a different fact than the one any reader asks for.
     */
    @Test
    void twoArgumentsOfOneFieldRankIndependentlyFromZero() {
        withSeededStore(GRAPH, dsl -> {
            seedUseSite(dsl, GRAPH, "in", "Payload");
            seedArgument(dsl, GRAPH, "Mutation", "write", "also", "Payload");
            seedInputField(dsl, GRAPH, "Payload", "value", "String", 0, false, false, null);
            seedOccurrencePath(dsl, GRAPH, "Mutation", "write", "in", "Payload",
                new OccurrenceStep("Payload", "value", "String"));
            seedOccurrencePath(dsl, GRAPH, "Mutation", "write", "also", "Payload",
                new OccurrenceStep("Payload", "value", "String"));

            assertThat(ranked(dsl)).containsExactlyInAnyOrder(
                "0 Mutation.write(also)",
                "0 Mutation.write(in)",
                "1 Mutation.write(also)/value",
                "1 Mutation.write(in)/value");
        });
    }

    /**
     * A graph ranks nothing on its sibling's behalf. The two graphs declare the same two fields of
     * the same input type in opposite orders, so a partition that lost a graph would have to change
     * one of the two answers rather than merely produce extra rows.
     */
    @Test
    void aGraphRanksNothingOnItsSiblingsBehalf() {
        withSeededStore(GRAPH, dsl -> {
            seedGraph(dsl, OTHER_GRAPH);
            seedUseSite(dsl, GRAPH, "in", "Payload");
            seedInputField(dsl, GRAPH, "Payload", "one", "String", 0, false, false, null);
            seedInputField(dsl, GRAPH, "Payload", "two", "String", 1, false, false, null);
            seedUseSite(dsl, OTHER_GRAPH, "in", "Payload");
            seedInputField(dsl, OTHER_GRAPH, "Payload", "two", "String", 0, false, false, null);
            seedInputField(dsl, OTHER_GRAPH, "Payload", "one", "String", 1, false, false, null);
            for (String graph : List.of(GRAPH, OTHER_GRAPH)) {
                seedOccurrencePath(dsl, graph, "Mutation", "write", "in", "Payload",
                    new OccurrenceStep("Payload", "one", "String"));
                seedOccurrencePath(dsl, graph, "Mutation", "write", "in", "Payload",
                    new OccurrenceStep("Payload", "two", "String"));
            }

            assertThat(allRanked(dsl)).containsExactlyInAnyOrder(
                GRAPH + " 0 Mutation.write(in)",
                GRAPH + " 1 Mutation.write(in)/one",
                GRAPH + " 2 Mutation.write(in)/two",
                OTHER_GRAPH + " 0 Mutation.write(in)",
                OTHER_GRAPH + " 1 Mutation.write(in)/two",
                OTHER_GRAPH + " 2 Mutation.write(in)/one");
        });
    }

    // ===== Fixtures =====

    /** One mutation field taking one argument of an input type the case then declares fields on. */
    private static void seedUseSite(DSLContext dsl, String graphName, String argumentName,
                                    String inputType) {
        seedDeclaredType(dsl, graphName, "Mutation", "OBJECT");
        seedDeclaredType(dsl, graphName, inputType, "INPUT_OBJECT");
        seedField(dsl, graphName, "Mutation", "write", "String", false);
        seedArgument(dsl, graphName, "Mutation", "write", argumentName, inputType);
    }

    // ===== Readings =====

    /** Every occurrence of the primary graph as {@code <rank> <path>}, read back in rank order. */
    private static List<String> ranked(DSLContext dsl) {
        return dsl.selectFrom(INTENT_INPUT_OCCURRENCE_DESCENT_ORDER)
            .where(INTENT_INPUT_OCCURRENCE_DESCENT_ORDER.GRAPH_NAME.eq(GRAPH))
            .orderBy(INTENT_INPUT_OCCURRENCE_DESCENT_ORDER.ORDINAL,
                INTENT_INPUT_OCCURRENCE_DESCENT_ORDER.PATH)
            .fetch(r -> r.getOrdinal() + " " + r.getPath());
    }

    /** The same, graph first, so the partition is read as a value rather than filtered away. */
    private static List<String> allRanked(DSLContext dsl) {
        return dsl.selectFrom(INTENT_INPUT_OCCURRENCE_DESCENT_ORDER)
            .fetch(r -> r.getGraphName() + " " + r.getOrdinal() + " " + r.getPath());
    }
}
