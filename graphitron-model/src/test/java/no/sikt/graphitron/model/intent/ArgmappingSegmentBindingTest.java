package no.sikt.graphitron.model.intent;

import no.sikt.graphitron.model.test.SeededStore.OccurrenceStep;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Consumer;

import static no.sikt.graphitron.model.Tables.INTENT_ARGMAPPING_SEGMENT_BINDING;
import static no.sikt.graphitron.model.test.SeededStore.seedArgument;
import static no.sikt.graphitron.model.test.SeededStore.seedArgumentConditionArgMappingPair;
import static no.sikt.graphitron.model.test.SeededStore.seedArgumentPathSegments;
import static no.sikt.graphitron.model.test.SeededStore.seedDeclaredType;
import static no.sikt.graphitron.model.test.SeededStore.seedField;
import static no.sikt.graphitron.model.test.SeededStore.seedFieldConditionArgMappingPair;
import static no.sikt.graphitron.model.test.SeededStore.seedFieldReferenceStepArgMappingPair;
import static no.sikt.graphitron.model.test.SeededStore.seedGraph;
import static no.sikt.graphitron.model.test.SeededStore.seedOccurrencePath;
import static no.sikt.graphitron.model.test.SeededStore.seedRoutineArgMappingPair;
import static no.sikt.graphitron.model.test.SeededStore.withSeededStore;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What {@code intent_argmapping_segment_binding} returns: one row per segment of an
 * {@code argMapping} path that names something reachable, and no row for a segment that names
 * nothing. The grain is the segment rather than the path, which is what lets a name that is not
 * there be an absence at a position rather than a verdict in a vocabulary.
 *
 * <p>Most cases here therefore read as a list of bound positions, and the interesting assertion is
 * usually where the list stops. A position that has a segment and no row is the relation's way of
 * saying that segment bound nothing, and because the rows are prefix-dense the stopping point is
 * the highest bound position rather than something a reader has to search for.
 *
 * <p>The keying is over {@code intent_input_occurrence_path}, joined through the segment
 * decomposition, so no case here states a serialized key for the view to split.
 */
class ArgmappingSegmentBindingTest {

    private static final String GRAPH = "g";

    // ===== Position zero: which slot a head may name is the site's own rule =====

    /**
     * At a {@code @routine} the head may name any argument of the field, and a single-segment path
     * binds that argument and stops there. Position zero being an {@code ARGUMENT} binding is what
     * lets the {@code @nodeId} join downstream reach the argument-grain relation.
     */
    @Test
    void aBareHeadBindsTheArgumentItNames() {
        withSeededStore(GRAPH, dsl -> {
            seedField(dsl, GRAPH, "Mutation", "rentFilm");
            seedArgument(dsl, GRAPH, "Mutation", "rentFilm", "inventoryId", "ID");
            routinePair(dsl, "Mutation", "rentFilm", 0, "pInventoryId", "inventoryId");

            var row = only(dsl);
            assertThat(row.get(INTENT_ARGMAPPING_SEGMENT_BINDING.SEGMENT_POSITION)).isZero();
            assertThat(row.get(INTENT_ARGMAPPING_SEGMENT_BINDING.SEGMENT_NAME))
                .isEqualTo("inventoryId");
            assertThat(row.get(INTENT_ARGMAPPING_SEGMENT_BINDING.BOUND_KIND)).isEqualTo("ARGUMENT");
            assertThat(row.get(INTENT_ARGMAPPING_SEGMENT_BINDING.BOUND_TYPE_NAME))
                .isEqualTo("Mutation");
            assertThat(row.get(INTENT_ARGMAPPING_SEGMENT_BINDING.BOUND_FIELD_NAME))
                .isEqualTo("rentFilm");
            assertThat(row.get(INTENT_ARGMAPPING_SEGMENT_BINDING.BOUND_ARGUMENT_NAME))
                .isEqualTo("inventoryId");
        });
    }

    /**
     * A head naming no argument of the field binds nothing, so the path has no rows at all rather
     * than a row saying it failed. The walk rejects this spelling before the store is written, so
     * restating it as a verdict here would be a second message for one mistake.
     */
    @Test
    void aHeadNamingNoArgumentBindsNothing() {
        withSeededStore(GRAPH, dsl -> {
            seedField(dsl, GRAPH, "Mutation", "rentFilm");
            seedArgument(dsl, GRAPH, "Mutation", "rentFilm", "input", "RentFilmInput");
            routinePair(dsl, "Mutation", "rentFilm", 0, "pNope", "notAnArgument");

            assertThat(rows(dsl)).isEmpty();
        });
    }

    /**
     * At an argument-site {@code @condition} the only slot in scope is the argument the directive
     * sits on, which is the walk's own rule. A head naming a sibling argument of the same field
     * therefore binds nothing here even though the same spelling binds at a field-site condition.
     */
    @Test
    void anArgumentSiteConditionAdmitsOnlyItsOwnArgument() {
        withSeededStore(GRAPH, dsl -> {
            seedField(dsl, GRAPH, "Query", "films");
            seedArgument(dsl, GRAPH, "Query", "films", "other", "ID");
            seedArgumentConditionArgMappingPair(dsl, GRAPH, "Query", "films", "byActor", 0,
                "p", "byActor");
            seedArgumentPathSegments(dsl, GRAPH, "Query", "films", "byActor");
            seedArgumentConditionArgMappingPair(dsl, GRAPH, "Query", "films", "other", 0,
                "p", "byActor");

            assertThat(boundPositions(dsl, "Query.films(byActor)")).containsExactly(0);
            assertThat(boundPositions(dsl, "Query.films(other)"))
                .as("the sibling argument is not in scope at this condition")
                .isEmpty();
        });
    }

    /**
     * At an input-field {@code @condition} the head names the input field the directive sits on, so
     * position zero binds against the field relation and needs no occurrence path at all. That
     * independence is what lets this arm answer for an input type nothing reaches.
     */
    @Test
    void anInputFieldConditionBindsItsOwnFieldWithNoOccurrencePath() {
        withSeededStore(GRAPH, dsl -> {
            seedDeclaredType(dsl, GRAPH, "RentFilmInput", "INPUT_OBJECT");
            seedField(dsl, GRAPH, "RentFilmInput", "inventoryId");
            seedFieldConditionArgMappingPair(dsl, GRAPH, "RentFilmInput", "inventoryId", 0,
                "p", "inventoryId");
            seedArgumentPathSegments(dsl, GRAPH, "RentFilmInput", "inventoryId", "inventoryId");

            var row = only(dsl);
            assertThat(row.get(INTENT_ARGMAPPING_SEGMENT_BINDING.SITE))
                .isEqualTo("INPUT_FIELD_CONDITION");
            assertThat(row.get(INTENT_ARGMAPPING_SEGMENT_BINDING.BOUND_KIND))
                .isEqualTo("INPUT_FIELD");
            assertThat(row.get(INTENT_ARGMAPPING_SEGMENT_BINDING.BOUND_TYPE_NAME))
                .isEqualTo("RentFilmInput");
            assertThat(row.get(INTENT_ARGMAPPING_SEGMENT_BINDING.BOUND_FIELD_NAME))
                .isEqualTo("inventoryId");
            assertThat(row.get(INTENT_ARGMAPPING_SEGMENT_BINDING.BOUND_ARGUMENT_NAME)).isNull();
        });
    }

    /**
     * A path-step {@code @condition} resolves against an empty slot map, so no arm fires and nothing
     * it spells binds at any position. Saying so keeps the emptiness a recorded fact rather than a
     * suspected bug in the view, and it is why those sites can only ever defer.
     */
    @Test
    void aPathStepConditionBindsNothingAtAnyPosition() {
        withSeededStore(GRAPH, dsl -> {
            seedField(dsl, GRAPH, "Film", "actors");
            seedArgument(dsl, GRAPH, "Film", "actors", "since", "String");
            seedFieldReferenceStepArgMappingPair(dsl, GRAPH, "Film", "actors", 0, 0, 0,
                "p", "since");
            seedArgumentPathSegments(dsl, GRAPH, "Film", "actors", "since");

            assertThat(rows(dsl))
                .as("a head that binds anywhere else binds nothing here")
                .isEmpty();
        });
    }

    // ===== Positions below the head, one per level of descent =====

    /**
     * The motivating shape: a dotted head descends into an input object and the key-column segment
     * beyond the input field binds nothing. Two bound positions out of three spelled segments is the
     * whole fact this item turns on, and the third position's absence is what a projection reads.
     */
    @Test
    void aDottedPathBindsTheHeadAndTheInputFieldButNotTheKeyColumn() {
        withRentFilmInput(dsl -> {
            routinePair(dsl, "Mutation", "rentFilm", 0, "pInventoryId",
                "input.inventoryId.inventory_id");

            assertThat(boundPositions(dsl, "Mutation.rentFilm#0")).containsExactly(0, 1);
            var deepest = at(dsl, 1);
            assertThat(deepest.get(INTENT_ARGMAPPING_SEGMENT_BINDING.BOUND_KIND))
                .isEqualTo("INPUT_FIELD");
            assertThat(deepest.get(INTENT_ARGMAPPING_SEGMENT_BINDING.BOUND_TYPE_NAME))
                .isEqualTo("RentFilmInput");
            assertThat(deepest.get(INTENT_ARGMAPPING_SEGMENT_BINDING.BOUND_FIELD_NAME))
                .isEqualTo("inventoryId");
        });
    }

    /**
     * A two-level descent binds a row at every level. Every prefix of an occurrence path being its
     * own row is what makes this a join per position rather than a walk, so the intermediate level
     * has to come out as its own row and not merely as a step the deepest match implies.
     */
    @Test
    void aTwoLevelDescentBindsEveryLevel() {
        withNestedInput(dsl -> {
            routinePair(dsl, "Mutation", "rentFilm", 0, "pInventoryId",
                "input.nested.inventoryId.inventory_id");

            assertThat(boundPositions(dsl, "Mutation.rentFilm#0")).containsExactly(0, 1, 2);
            assertThat(at(dsl, 1).get(INTENT_ARGMAPPING_SEGMENT_BINDING.BOUND_FIELD_NAME))
                .isEqualTo("nested");
            assertThat(at(dsl, 2).get(INTENT_ARGMAPPING_SEGMENT_BINDING.BOUND_TYPE_NAME))
                .isEqualTo("NestedInput");
            assertThat(at(dsl, 2).get(INTENT_ARGMAPPING_SEGMENT_BINDING.BOUND_FIELD_NAME))
                .isEqualTo("inventoryId");
        });
    }

    /**
     * A segment naming no input field stops the binding there, and the segments beyond it bind
     * nothing either. The stop is what makes the highest bound position meaningful: a reader adds
     * one to it to name the first segment that resolved to nothing.
     */
    @Test
    void aSegmentNamingNoInputFieldStopsTheBinding() {
        withRentFilmInput(dsl -> {
            routinePair(dsl, "Mutation", "rentFilm", 0, "pNope", "input.nope.deeper");

            assertThat(boundPositions(dsl, "Mutation.rentFilm#0")).containsExactly(0);
        });
    }

    /**
     * The rows of a pair are prefix-dense: bound positions run from zero with no hole. Downstream
     * readers take the leaf to be the bound position with no bound successor, which identifies one
     * row only if a gap cannot occur, so this is the property that definition rests on.
     */
    @Test
    void boundPositionsAreDenseFromZero() {
        withNestedInput(dsl -> {
            routinePair(dsl, "Mutation", "rentFilm", 0, "pInventoryId",
                "input.nested.inventoryId.inventory_id.andMore");

            var positions = boundPositions(dsl, "Mutation.rentFilm#0");
            assertThat(positions).isNotEmpty();
            assertThat(positions).containsExactlyElementsOf(
                java.util.stream.IntStream.range(0, positions.size()).boxed().toList());
        });
    }

    /**
     * A dotted head at an input-field {@code @condition} anchors on a step whose container is the
     * pair's own type, then walks from there. Anchoring on the container is what makes it a join
     * rather than a name coincidence: matching the field name alone would accept a path ending in a
     * field of that name whatever input type owned it.
     */
    @Test
    void aDottedInputFieldConditionAnchorsOnItsOwnContainer() {
        withNestedInput(dsl -> {
            seedFieldConditionArgMappingPair(dsl, GRAPH, "RentFilmInput", "nested", 0,
                "p", "nested.inventoryId.inventory_id");
            seedArgumentPathSegments(dsl, GRAPH, "RentFilmInput", "nested",
                "nested.inventoryId.inventory_id");

            assertThat(boundPositions(dsl, "RentFilmInput.nested")).containsExactly(0, 1);
            assertThat(at(dsl, 1).get(INTENT_ARGMAPPING_SEGMENT_BINDING.BOUND_TYPE_NAME))
                .isEqualTo("NestedInput");
            assertThat(at(dsl, 1).get(INTENT_ARGMAPPING_SEGMENT_BINDING.BOUND_FIELD_NAME))
                .isEqualTo("inventoryId");
        });
    }

    /**
     * An input type reached from two arguments has one occurrence step per reaching path, so the
     * input-field-rooted arm's anchor join is one-to-many while its answer is one: the leaf is fixed
     * by descending input-field types from the head, which is a definition-side fact independent of
     * which argument reached the type. One row per position is therefore the arity, and the tied
     * rows cannot disagree.
     */
    @Test
    void anInputTypeReachedTwiceStillBindsOneRowPerPosition() {
        withSeededStore(GRAPH, dsl -> {
            seedDeclaredType(dsl, GRAPH, "RentFilmInput", "INPUT_OBJECT");
            seedDeclaredType(dsl, GRAPH, "NestedInput", "INPUT_OBJECT");
            seedField(dsl, GRAPH, "RentFilmInput", "nested", "NestedInput", false);
            seedField(dsl, GRAPH, "NestedInput", "inventoryId");
            seedField(dsl, GRAPH, "Mutation", "rentFilm");
            seedArgument(dsl, GRAPH, "Mutation", "rentFilm", "input", "RentFilmInput");
            seedField(dsl, GRAPH, "Mutation", "returnFilm");
            seedArgument(dsl, GRAPH, "Mutation", "returnFilm", "other", "RentFilmInput");
            seedOccurrencePath(dsl, GRAPH, "Mutation", "rentFilm", "input", "RentFilmInput",
                new OccurrenceStep("RentFilmInput", "nested", "NestedInput"),
                new OccurrenceStep("NestedInput", "inventoryId", "ID"));
            seedOccurrencePath(dsl, GRAPH, "Mutation", "returnFilm", "other", "RentFilmInput",
                new OccurrenceStep("RentFilmInput", "nested", "NestedInput"),
                new OccurrenceStep("NestedInput", "inventoryId", "ID"));
            seedFieldConditionArgMappingPair(dsl, GRAPH, "RentFilmInput", "nested", 0,
                "p", "nested.inventoryId");
            seedArgumentPathSegments(dsl, GRAPH, "RentFilmInput", "nested", "nested.inventoryId");

            assertThat(boundPositions(dsl, "RentFilmInput.nested"))
                .as("two reaching paths, one answer per position")
                .containsExactly(0, 1);
        });
    }

    // ===== The grain is the pair's own, and the segment position within it =====

    /**
     * Two applications of one repeatable directive each bind their own path. Collapsing them to the
     * field coordinate would answer for one and drop the other silently, which is the one move the
     * nearest sibling view makes that this one must not.
     */
    @Test
    void twoRoutineApplicationsEachBindTheirOwnPath() {
        withRentFilmInput(dsl -> {
            routinePair(dsl, "Mutation", "rentFilm", 0, "pInventoryId",
                "input.inventoryId.inventory_id");
            routinePair(dsl, "Mutation", "rentFilm", 1, "pNope", "input.nope");

            assertThat(boundPositions(dsl, "Mutation.rentFilm#0")).containsExactly(0, 1);
            assertThat(boundPositions(dsl, "Mutation.rentFilm#1")).containsExactly(0);
        });
    }

    /**
     * The graph partition holds through every join. The occurrence relations and the segment
     * decomposition are each joined on {@code graph_name} in three places, so a sibling graph
     * spelling the same path is the case that keeps those conjuncts from being decorative.
     */
    @Test
    void aSiblingGraphsSegmentsAreNotThisGraphsRows() {
        withSeededStore(GRAPH, dsl -> {
            seedGraph(dsl, "other");
            seedDeclaredType(dsl, "other", "RentFilmInput", "INPUT_OBJECT");
            seedField(dsl, "other", "RentFilmInput", "inventoryId");
            seedField(dsl, "other", "Mutation", "rentFilm");
            seedArgument(dsl, "other", "Mutation", "rentFilm", "input", "RentFilmInput");
            seedOccurrencePath(dsl, "other", "Mutation", "rentFilm", "input", "RentFilmInput",
                new OccurrenceStep("RentFilmInput", "inventoryId", "ID"));
            seedRoutineArgMappingPair(dsl, "other", "Mutation", "rentFilm", 0, 0,
                "pInventoryId", "input.inventoryId");
            seedArgumentPathSegments(dsl, "other", "Mutation", "rentFilm", "input.inventoryId");

            assertThat(rows(dsl)).isEmpty();
        });
    }

    // ===== Fixtures =====

    /**
     * {@code Mutation.rentFilm(input: RentFilmInput)} with one input field, and the occurrence rows
     * a capture-cadence pass would have left behind for it.
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

    /** {@link #withRentFilmInput} with a second level under it, for the per-level cases. */
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

    /** A {@code @routine} pair at an ordinal, with its segment decomposition beside it. */
    private static void routinePair(DSLContext dsl, String typeName, String fieldName, int ordinal,
                                    String paramName, String argumentPath) {
        seedRoutineArgMappingPair(dsl, GRAPH, typeName, fieldName, ordinal, 0, paramName,
            argumentPath);
        seedArgumentPathSegments(dsl, GRAPH, typeName, fieldName, argumentPath);
    }

    // ===== Reads =====

    /** Every row of the graph under assertion. */
    private static List<Record> rows(DSLContext dsl) {
        return dsl.select(INTENT_ARGMAPPING_SEGMENT_BINDING.fields())
            .from(INTENT_ARGMAPPING_SEGMENT_BINDING)
            .where(INTENT_ARGMAPPING_SEGMENT_BINDING.GRAPH_NAME.eq(GRAPH))
            .fetch()
            .stream()
            .map(Record.class::cast)
            .toList();
    }

    /** The bound segment positions at one use site, ascending. */
    private static List<Integer> boundPositions(DSLContext dsl, String useSite) {
        return rows(dsl).stream()
            .filter(r -> useSite.equals(r.get(INTENT_ARGMAPPING_SEGMENT_BINDING.USE_SITE)))
            .map(r -> r.get(INTENT_ARGMAPPING_SEGMENT_BINDING.SEGMENT_POSITION))
            .sorted()
            .toList();
    }

    /** The one row at a segment position, single-pair fixtures being the only callers. */
    private static Record at(DSLContext dsl, int segmentPosition) {
        var matching = rows(dsl).stream()
            .filter(r -> r.get(INTENT_ARGMAPPING_SEGMENT_BINDING.SEGMENT_POSITION)
                == segmentPosition)
            .toList();
        assertThat(matching).as("one binding per segment position").hasSize(1);
        return matching.getFirst();
    }

    /** The one row a single-segment fixture produces. */
    private static Record only(DSLContext dsl) {
        var all = rows(dsl);
        assertThat(all).hasSize(1);
        return all.getFirst();
    }
}
