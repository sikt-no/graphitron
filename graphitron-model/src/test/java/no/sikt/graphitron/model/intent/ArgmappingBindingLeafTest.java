package no.sikt.graphitron.model.intent;

import no.sikt.graphitron.model.test.SeededStore.OccurrenceStep;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static no.sikt.graphitron.model.Tables.INTENT_ARGMAPPING_BINDING_LEAF;
import static no.sikt.graphitron.model.test.SeededStore.derive;
import static no.sikt.graphitron.model.test.SeededStore.seedArgument;
import static no.sikt.graphitron.model.test.SeededStore.seedArgumentConditionArgMappingPair;
import static no.sikt.graphitron.model.test.SeededStore.seedArgumentNodeId;
import static no.sikt.graphitron.model.test.SeededStore.seedArgumentPathSegments;
import static no.sikt.graphitron.model.test.SeededStore.seedDeclaredType;
import static no.sikt.graphitron.model.test.SeededStore.seedField;
import static no.sikt.graphitron.model.test.SeededStore.seedFieldConditionArgMappingPair;
import static no.sikt.graphitron.model.test.SeededStore.seedFieldNodeId;
import static no.sikt.graphitron.model.test.SeededStore.seedFieldReferenceStepArgMappingPair;
import static no.sikt.graphitron.model.test.SeededStore.seedOccurrencePath;
import static no.sikt.graphitron.model.test.SeededStore.seedRoutineArgMappingPair;
import static no.sikt.graphitron.model.test.SeededStore.withSeededStore;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What {@code intent_argmapping_binding_leaf} returns: the last thing an {@code argMapping} path
 * bound, whether that thing carries a {@code @nodeId}, and how many segments the path spells beyond
 * it. A reduction over {@code intent_argmapping_segment_binding} rather than a resolution of its
 * own, so the cases here are about the reduction and its three added columns; where the binding
 * itself stops is the sibling suite's subject.
 *
 * <p>Absence means the path bound nothing at all, which happens two ways: at a path-step
 * {@code @condition}, where the walk resolves against an empty slot map, and at any other site where
 * the head names no slot in scope. Both are rejections {@code ArgBindingMap.of} already returns
 * before the store is written, so the absence here is the lack of a leaf and not a fact withheld.
 *
 * <p>An ordinary binding is a row, not a gap: {@code node_id_declared} false is what says no decode
 * is implied. The three {@code @nodeId} readings are deliberately three answers across two columns,
 * because a two-value fork would have to put the bare spelling on one side or the other and either
 * choice makes it indistinguishable from something it is not.
 */
class ArgmappingBindingLeafTest {

    private static final String GRAPH = "g";

    // ===== The leaf is where the binding stopped =====

    /**
     * A single-segment path leaves the head as the leaf with nothing trailing. This is the arm the
     * silently-wrong case runs through: today such a binding hands a routine parameter the base64
     * wire id and nothing says a word, and a declared node id with zero trailing segments is what a
     * rejection keys on.
     */
    @Test
    void aBareNodeIdArgumentHeadIsTheLeafWithNothingTrailing() {
        withSeededStore(GRAPH, dsl -> {
            seedField(dsl, GRAPH, "Mutation", "rentFilm");
            seedArgument(dsl, GRAPH, "Mutation", "rentFilm", "inventoryId", "ID");
            seedArgumentNodeId(dsl, GRAPH, "Mutation", "rentFilm", "inventoryId", "Inventory");
            pair(dsl, "Mutation", "rentFilm", 0, "pInventoryId", "inventoryId");

            var row = only(dsl);
            assertThat(row.get(INTENT_ARGMAPPING_BINDING_LEAF.SEGMENT_POSITION)).isZero();
            assertThat(row.get(INTENT_ARGMAPPING_BINDING_LEAF.BOUND_KIND)).isEqualTo("ARGUMENT");
            assertThat(row.get(INTENT_ARGMAPPING_BINDING_LEAF.BOUND_ARGUMENT_NAME))
                .isEqualTo("inventoryId");
            assertThat(row.get(INTENT_ARGMAPPING_BINDING_LEAF.NODE_ID_DECLARED)).isTrue();
            assertThat(row.get(INTENT_ARGMAPPING_BINDING_LEAF.NODE_TYPE_REF))
                .isEqualTo("Inventory");
            assertThat(row.get(INTENT_ARGMAPPING_BINDING_LEAF.TRAILING_SEGMENTS)).isZero();
        });
    }

    /**
     * The motivating case: a dotted head descends onto a {@code @nodeId} input field and one segment
     * is left over. One trailing segment is the projection this item enables, and the count is what
     * tells it from a typo.
     */
    @Test
    void aDottedPathOntoANodeIdInputFieldLeavesOneSegmentTrailing() {
        withRentFilmInput(dsl -> {
            seedFieldNodeId(dsl, GRAPH, "RentFilmInput", "inventoryId", "Inventory");
            pair(dsl, "Mutation", "rentFilm", 0, "pInventoryId", "input.inventoryId.inventory_id");

            var row = only(dsl);
            assertThat(row.get(INTENT_ARGMAPPING_BINDING_LEAF.SEGMENT_POSITION)).isEqualTo(1);
            assertThat(row.get(INTENT_ARGMAPPING_BINDING_LEAF.BOUND_KIND)).isEqualTo("INPUT_FIELD");
            assertThat(row.get(INTENT_ARGMAPPING_BINDING_LEAF.BOUND_TYPE_NAME))
                .isEqualTo("RentFilmInput");
            assertThat(row.get(INTENT_ARGMAPPING_BINDING_LEAF.BOUND_FIELD_NAME))
                .isEqualTo("inventoryId");
            assertThat(row.get(INTENT_ARGMAPPING_BINDING_LEAF.BOUND_ARGUMENT_NAME)).isNull();
            assertThat(row.get(INTENT_ARGMAPPING_BINDING_LEAF.NODE_TYPE_REF))
                .isEqualTo("Inventory");
            assertThat(row.get(INTENT_ARGMAPPING_BINDING_LEAF.TRAILING_SEGMENTS)).isEqualTo(1);
        });
    }

    /**
     * The deepest bound position wins, not the first. Every prefix of an occurrence path is its own
     * binding row, so the reduction has to be the row with no bound successor rather than any row
     * that happens to bind.
     */
    @Test
    void theLeafIsTheDeepestBoundPosition() {
        withNestedInput(dsl -> {
            seedFieldNodeId(dsl, GRAPH, "NestedInput", "inventoryId", "Inventory");
            pair(dsl, "Mutation", "rentFilm", 0, "pInventoryId",
                "input.nested.inventoryId.inventory_id");

            var row = only(dsl);
            assertThat(row.get(INTENT_ARGMAPPING_BINDING_LEAF.SEGMENT_POSITION)).isEqualTo(2);
            assertThat(row.get(INTENT_ARGMAPPING_BINDING_LEAF.BOUND_TYPE_NAME))
                .isEqualTo("NestedInput");
            assertThat(row.get(INTENT_ARGMAPPING_BINDING_LEAF.BOUND_FIELD_NAME))
                .isEqualTo("inventoryId");
            assertThat(row.get(INTENT_ARGMAPPING_BINDING_LEAF.TRAILING_SEGMENTS)).isEqualTo(1);
        });
    }

    /**
     * One leaf per pair even when the path descends several levels. The reduction identifies exactly
     * one row only because the binding rows are prefix-dense, so a fixture whose descent has an
     * intermediate level is where that would show up if it were not.
     */
    @Test
    void aMultiLevelDescentReducesToOneLeaf() {
        withNestedInput(dsl -> {
            pair(dsl, "Mutation", "rentFilm", 0, "pNested", "input.nested.inventoryId");

            assertThat(rows(dsl)).hasSize(1);
            assertThat(only(dsl).get(INTENT_ARGMAPPING_BINDING_LEAF.SEGMENT_POSITION)).isEqualTo(2);
        });
    }

    // ===== Where the path bound nothing there is no leaf =====

    /**
     * A head naming no argument of the field binds nothing, so there is no leaf row. The walk
     * rejects this spelling before the store is written; the absence here says there is no leaf, not
     * that the mistake went unnoticed.
     */
    @Test
    void aHeadNamingNoArgumentHasNoLeaf() {
        withSeededStore(GRAPH, dsl -> {
            seedField(dsl, GRAPH, "Mutation", "rentFilm");
            seedArgument(dsl, GRAPH, "Mutation", "rentFilm", "input", "RentFilmInput");
            pair(dsl, "Mutation", "rentFilm", 0, "pNope", "notAnArgument");

            assertThat(rows(dsl)).isEmpty();
        });
    }

    /**
     * A path-step {@code @condition} binds nothing at any position, so it has no leaf whatever it
     * spells. Saying so keeps the emptiness a recorded fact rather than a suspected bug in the
     * reduction, and it is why those sites can only ever defer.
     */
    @Test
    void aPathStepConditionHasNoLeaf() {
        withSeededStore(GRAPH, dsl -> {
            seedField(dsl, GRAPH, "Film", "actors");
            seedArgument(dsl, GRAPH, "Film", "actors", "since", "String");
            seedArgumentNodeId(dsl, GRAPH, "Film", "actors", "since", "Inventory");
            seedFieldReferenceStepArgMappingPair(dsl, GRAPH, "Film", "actors", 0, 0, 0,
                "p", "since");
            seedArgumentPathSegments(dsl, GRAPH, "Film", "actors", "since");

            assertThat(rows(dsl))
                .as("a head that would bind anywhere else binds nothing here")
                .isEmpty();
        });
    }

    // ===== The three @nodeId readings =====

    /**
     * An ordinary binding is a row with no declared node id. This is what replaces an absence: the
     * relation says what every path bound, and the column says whether a decode is implied, so a
     * reader never has to tell "no decode" from "no answer" by the shape of an empty result.
     */
    @Test
    void anOrdinaryBindingIsARowWithNoDeclaredNodeId() {
        withRentFilmInput(dsl -> {
            pair(dsl, "Mutation", "rentFilm", 0, "pInventoryId", "input.inventoryId");

            var row = only(dsl);
            assertThat(row.get(INTENT_ARGMAPPING_BINDING_LEAF.NODE_ID_DECLARED)).isFalse();
            assertThat(row.get(INTENT_ARGMAPPING_BINDING_LEAF.NODE_TYPE_REF)).isNull();
            assertThat(row.get(INTENT_ARGMAPPING_BINDING_LEAF.TRAILING_SEGMENTS)).isZero();
        });
    }

    /**
     * A bare {@code @nodeId} with no {@code typeName:} declares a decode and names nothing to decode
     * against, which is the arm the missing-{@code typeName:} rejection reads. The two columns
     * disagreeing is the whole point: collapsing them would make this the same row as an ordinary
     * binding, and an ordinary binding is exactly what it must not be taken for.
     */
    @Test
    void aBareNodeIdDeclaresADecodeAndNamesNoType() {
        withSeededStore(GRAPH, dsl -> {
            seedField(dsl, GRAPH, "Mutation", "rentFilm");
            seedArgument(dsl, GRAPH, "Mutation", "rentFilm", "inventoryId", "ID");
            seedArgumentNodeId(dsl, GRAPH, "Mutation", "rentFilm", "inventoryId", null);
            pair(dsl, "Mutation", "rentFilm", 0, "pInventoryId", "inventoryId");

            var row = only(dsl);
            assertThat(row.get(INTENT_ARGMAPPING_BINDING_LEAF.NODE_ID_DECLARED)).isTrue();
            assertThat(row.get(INTENT_ARGMAPPING_BINDING_LEAF.NODE_TYPE_REF)).isNull();
        });
    }

    /**
     * The {@code @nodeId} lookup follows {@code bound_kind}: an argument leaf reads the argument-grain
     * relation and an input-field leaf the field-grain one. A directive on the input field of the
     * same name must not answer for an argument leaf, which is what this pair of coordinates checks.
     */
    @Test
    void theNodeIdLookupFollowsTheBoundKind() {
        withRentFilmInput(dsl -> {
            seedArgumentNodeId(dsl, GRAPH, "Mutation", "rentFilm", "input", "Inventory");
            seedFieldNodeId(dsl, GRAPH, "RentFilmInput", "inventoryId", "Film");
            pair(dsl, "Mutation", "rentFilm", 0, 0, "pDeep", "input.inventoryId");
            pair(dsl, "Mutation", "rentFilm", 0, 1, "pHead", "input");

            assertThat(rowAt(dsl, 0).orElseThrow().get(INTENT_ARGMAPPING_BINDING_LEAF.NODE_TYPE_REF))
                .as("the input-field leaf reads the field-grain directive")
                .isEqualTo("Film");
            assertThat(rowAt(dsl, 1).orElseThrow().get(INTENT_ARGMAPPING_BINDING_LEAF.NODE_TYPE_REF))
                .as("the argument leaf reads the argument-grain directive")
                .isEqualTo("Inventory");
        });
    }

    // ===== Trailing segments are counted, not flagged =====

    /**
     * Two trailing segments must not read as a projection. One is this item's form; two is a typo or
     * a nested form neither this relation nor its readers claim to resolve, and the count is what
     * lets the two rejections say different things.
     */
    @Test
    void twoTrailingSegmentsAreCountedRatherThanCollapsed() {
        withRentFilmInput(dsl -> {
            seedFieldNodeId(dsl, GRAPH, "RentFilmInput", "inventoryId", "Inventory");
            pair(dsl, "Mutation", "rentFilm", 0, "pInventoryId",
                "input.inventoryId.inventory_id.nope");

            var row = only(dsl);
            assertThat(row.get(INTENT_ARGMAPPING_BINDING_LEAF.SEGMENT_POSITION)).isEqualTo(1);
            assertThat(row.get(INTENT_ARGMAPPING_BINDING_LEAF.TRAILING_SEGMENTS)).isEqualTo(2);
        });
    }

    /**
     * A segment naming no input field below a leaf that carries no {@code @nodeId} counts as
     * trailing. The count is over the segments the author spelled beyond where the path stopped, so
     * a name that resolved to nothing is a trailing segment whether or not a decode was intended;
     * that is what makes the count the same arithmetic in every case.
     */
    @Test
    void aSegmentNamingNoInputFieldCountsAsTrailing() {
        withRentFilmInput(dsl -> {
            pair(dsl, "Mutation", "rentFilm", 0, "pNope", "input.nope");

            var row = only(dsl);
            assertThat(row.get(INTENT_ARGMAPPING_BINDING_LEAF.SEGMENT_POSITION))
                .as("the path stopped at the head")
                .isZero();
            assertThat(row.get(INTENT_ARGMAPPING_BINDING_LEAF.BOUND_KIND)).isEqualTo("ARGUMENT");
            assertThat(row.get(INTENT_ARGMAPPING_BINDING_LEAF.NODE_ID_DECLARED)).isFalse();
            assertThat(row.get(INTENT_ARGMAPPING_BINDING_LEAF.TRAILING_SEGMENTS)).isEqualTo(1);
        });
    }

    /**
     * An input type no argument reaches has no occurrence path to descend, so a dotted head there
     * stops at the head and counts the rest as trailing. A bare head at the same coordinate binds
     * regardless, which is the pair of facts this case states together.
     */
    @Test
    void aDottedHeadOnAnUnreachedInputTypeStopsAtTheHead() {
        withSeededStore(GRAPH, dsl -> {
            seedDeclaredType(dsl, GRAPH, "Orphan", "INPUT_OBJECT");
            seedField(dsl, GRAPH, "Orphan", "inner", "Inner", false);
            seedDeclaredType(dsl, GRAPH, "Inner", "INPUT_OBJECT");
            seedField(dsl, GRAPH, "Inner", "inventoryId");
            seedFieldNodeId(dsl, GRAPH, "Inner", "inventoryId", "Inventory");
            seedFieldConditionArgMappingPair(dsl, GRAPH, "Orphan", "inner", 0,
                "p", "inner.inventoryId");
            seedArgumentPathSegments(dsl, GRAPH, "Orphan", "inner", "inner.inventoryId");

            var row = only(dsl);
            assertThat(row.get(INTENT_ARGMAPPING_BINDING_LEAF.SEGMENT_POSITION)).isZero();
            assertThat(row.get(INTENT_ARGMAPPING_BINDING_LEAF.BOUND_FIELD_NAME)).isEqualTo("inner");
            assertThat(row.get(INTENT_ARGMAPPING_BINDING_LEAF.NODE_ID_DECLARED))
                .as("the @nodeId below was never reached")
                .isFalse();
            assertThat(row.get(INTENT_ARGMAPPING_BINDING_LEAF.TRAILING_SEGMENTS)).isEqualTo(1);
        });
    }

    // ===== The grain is the pair's own =====

    /**
     * Two applications of one repeatable directive each reduce to their own leaf. Collapsing them to
     * the field coordinate would answer for one and drop the other silently, which is the one move
     * the nearest sibling view makes that this one must not.
     */
    @Test
    void twoRoutineApplicationsEachReduceToTheirOwnLeaf() {
        withRentFilmInput(dsl -> {
            seedFieldNodeId(dsl, GRAPH, "RentFilmInput", "inventoryId", "Inventory");
            pair(dsl, "Mutation", "rentFilm", 0, "pInventoryId", "input.inventoryId.inventory_id");
            pair(dsl, "Mutation", "rentFilm", 1, "pInventoryId", "input.inventoryId");

            var all = rows(dsl);
            assertThat(all).hasSize(2);
            assertThat(all.stream()
                .map(r -> r.get(INTENT_ARGMAPPING_BINDING_LEAF.TRAILING_SEGMENTS)))
                .containsExactlyInAnyOrder(1, 0);
            assertThat(all.stream().map(r -> r.get(INTENT_ARGMAPPING_BINDING_LEAF.USE_SITE)))
                .containsExactlyInAnyOrder("Mutation.rentFilm#0", "Mutation.rentFilm#1");
        });
    }

    /** Two pairs of one application each reduce to their own leaf, position being part of the grain. */
    @Test
    void twoPairsOfOneApplicationEachReduceToTheirOwnLeaf() {
        withRentFilmInput(dsl -> {
            seedFieldNodeId(dsl, GRAPH, "RentFilmInput", "inventoryId", "Inventory");
            pair(dsl, "Mutation", "rentFilm", 0, 0, "pInventoryId",
                "input.inventoryId.inventory_id");
            pair(dsl, "Mutation", "rentFilm", 0, 1, "pOther", "input.inventoryId");

            assertThat(rows(dsl)).hasSize(2);
            assertThat(rows(dsl).stream().map(r -> r.get(INTENT_ARGMAPPING_BINDING_LEAF.POSITION)))
                .containsExactlyInAnyOrder(0, 1);
        });
    }

    /**
     * At an argument-site {@code @condition} the only slot in scope is the argument the directive
     * sits on, so a head naming a sibling argument reduces to no leaf while the directive's own
     * argument reduces to one. Site-dependent scope surviving the reduction is what keeps the leaf
     * reading uniform across the eight sites.
     */
    @Test
    void anArgumentSiteConditionReducesOnlyItsOwnArgument() {
        withSeededStore(GRAPH, dsl -> {
            seedField(dsl, GRAPH, "Query", "films");
            seedArgument(dsl, GRAPH, "Query", "films", "other", "ID");
            seedArgumentNodeId(dsl, GRAPH, "Query", "films", "byActor", "Actor");
            seedArgumentConditionArgMappingPair(dsl, GRAPH, "Query", "films", "byActor", 0,
                "p", "byActor");
            seedArgumentPathSegments(dsl, GRAPH, "Query", "films", "byActor");
            seedArgumentConditionArgMappingPair(dsl, GRAPH, "Query", "films", "other", 0,
                "p", "byActor");

            assertThat(rowFor(dsl, "Query.films(byActor)").orElseThrow()
                .get(INTENT_ARGMAPPING_BINDING_LEAF.NODE_TYPE_REF))
                .isEqualTo("Actor");
            assertThat(rowFor(dsl, "Query.films(other)"))
                .as("the sibling argument is not in scope at this condition")
                .isEmpty();
        });
    }

    // ===== Fixtures =====

    /**
     * {@code Mutation.rentFilm(input: RentFilmInput)} with one {@code @nodeId}-able input field, and
     * the occurrence rows a capture-cadence pass would have left behind for it.
     */
    private static void withRentFilmInput(Consumer<DSLContext> body) {
        withSeededStore(GRAPH, dsl -> {
            seedDeclaredType(dsl, GRAPH, "RentFilmInput", "INPUT_OBJECT");
            seedField(dsl, GRAPH, "RentFilmInput", "inventoryId");
            seedField(dsl, GRAPH, "Mutation", "rentFilm");
            seedArgument(dsl, GRAPH, "Mutation", "rentFilm", "input", "RentFilmInput");
            seedOccurrencePath(dsl, GRAPH, "Mutation", "rentFilm", "input", "RentFilmInput",
                new OccurrenceStep("RentFilmInput", "inventoryId", "ID"));
            body.accept(dsl);
        });
    }

    /** {@link #withRentFilmInput} with a second level under it, for the deepest-position cases. */
    private static void withNestedInput(Consumer<DSLContext> body) {
        withSeededStore(GRAPH, dsl -> {
            seedDeclaredType(dsl, GRAPH, "RentFilmInput", "INPUT_OBJECT");
            seedDeclaredType(dsl, GRAPH, "NestedInput", "INPUT_OBJECT");
            seedField(dsl, GRAPH, "RentFilmInput", "nested", "NestedInput", false);
            seedField(dsl, GRAPH, "NestedInput", "inventoryId");
            seedField(dsl, GRAPH, "Mutation", "rentFilm");
            seedArgument(dsl, GRAPH, "Mutation", "rentFilm", "input", "RentFilmInput");
            seedOccurrencePath(dsl, GRAPH, "Mutation", "rentFilm", "input", "RentFilmInput",
                new OccurrenceStep("RentFilmInput", "nested", "NestedInput"),
                new OccurrenceStep("NestedInput", "inventoryId", "ID"));
            body.accept(dsl);
        });
    }

    /** A {@code @routine} pair at ordinal zero, with its segment decomposition beside it. */
    private static void pair(DSLContext dsl, String typeName, String fieldName, int ordinal,
                             String paramName, String argumentPath) {
        pair(dsl, typeName, fieldName, ordinal, 0, paramName, argumentPath);
    }

    /** A {@code @routine} pair at an ordinal and a position the case names. */
    private static void pair(DSLContext dsl, String typeName, String fieldName, int ordinal,
                             int position, String paramName, String argumentPath) {
        seedRoutineArgMappingPair(dsl, GRAPH, typeName, fieldName, ordinal, position, paramName,
            argumentPath);
        seedArgumentPathSegments(dsl, GRAPH, typeName, fieldName, argumentPath);
    }

    // ===== Reads =====

    /** Every row of the graph under assertion. */
    private static List<Record> rows(DSLContext dsl) {
        derive(dsl);
        return dsl.select(INTENT_ARGMAPPING_BINDING_LEAF.fields())
            .from(INTENT_ARGMAPPING_BINDING_LEAF)
            .where(INTENT_ARGMAPPING_BINDING_LEAF.GRAPH_NAME.eq(GRAPH))
            .fetch()
            .stream()
            .map(Record.class::cast)
            .toList();
    }

    /** The one row at a use-site coordinate, the relation's grain being site plus that key. */
    private static Optional<Record> rowFor(DSLContext dsl, String useSite) {
        var matching = rows(dsl).stream()
            .filter(r -> useSite.equals(r.get(INTENT_ARGMAPPING_BINDING_LEAF.USE_SITE)))
            .toList();
        assertThat(matching)
            .as("one row per use site and position")
            .hasSizeLessThanOrEqualTo(1);
        return matching.isEmpty() ? Optional.empty() : Optional.of(matching.getFirst());
    }

    /** The one row at an argMapping-list position, for fixtures pairing two spellings. */
    private static Optional<Record> rowAt(DSLContext dsl, int position) {
        var matching = rows(dsl).stream()
            .filter(r -> r.get(INTENT_ARGMAPPING_BINDING_LEAF.POSITION) == position)
            .toList();
        assertThat(matching).as("one leaf per position").hasSizeLessThanOrEqualTo(1);
        return matching.isEmpty() ? Optional.empty() : Optional.of(matching.getFirst());
    }

    /** The one row a single-pair fixture produces. */
    private static Record only(DSLContext dsl) {
        var all = rows(dsl);
        assertThat(all).hasSize(1);
        return all.getFirst();
    }
}
