package no.sikt.graphitron.model.intent;

import no.sikt.graphitron.model.test.SeededStore.OccurrenceStep;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static no.sikt.graphitron.model.Tables.INTENT_ARGMAPPING_BINDING_LEAF;
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
 * What {@code intent_argmapping_binding_leaf} returns: what an {@code argMapping} path binds to,
 * where the leaf it reaches carries a {@code @nodeId}, and how many trailing segments the descent
 * did not consume. The keying is over {@code intent_input_occurrence_path}, joined through the
 * segment decomposition, so no case here states a serialized key for the view to split.
 *
 * <p>Absence is the relation's central claim rather than a gap in it, so several cases assert no
 * row. It means exactly one thing: the path consumed every segment and its leaf carries no
 * {@code @nodeId}, which is the ordinary binding. Reading those cases as untested behaviour gets the
 * relation backwards. The complement is a row on purpose: a path that left segments unconsumed on a
 * leaf carrying no {@code @nodeId} names something that is not there, and the walk is silent on it
 * for the same reason it is silent on the projection this item exists to enable.
 *
 * <p>Every silence has its own case, because the three of them are different facts sharing one
 * remedy-shaped shrug: a path-step site resolves nothing at all, an unreached input type has no
 * occurrence path to descend, and a head naming no slot is a typo.
 */
class ArgmappingBindingLeafTest {

    private static final String GRAPH = "g";

    // ===== The bare head, which is the silent-base64 shape =====

    /**
     * A bare head naming a {@code @nodeId} argument resolves to that argument with nothing
     * unconsumed. This is the arm the whole silently-wrong case runs through: today such a binding
     * hands a routine parameter the base64 wire id and nothing says a word, and a zero here is what
     * a rejection keys on.
     */
    @Test
    void aBareNodeIdArgumentHeadResolvesWithNothingUnconsumed() {
        withSeededStore(GRAPH, dsl -> {
            seedField(dsl, GRAPH, "Mutation", "rentFilm");
            seedArgument(dsl, GRAPH, "Mutation", "rentFilm", "inventoryId", "ID");
            seedArgumentNodeId(dsl, GRAPH, "Mutation", "rentFilm", "inventoryId", "Inventory");
            pair(dsl, "Mutation", "rentFilm", 0, "pInventoryId", "inventoryId");

            var row = only(dsl);
            assertThat(row.get(INTENT_ARGMAPPING_BINDING_LEAF.DISPOSITION)).isEqualTo("RESOLVE");
            assertThat(row.get(INTENT_ARGMAPPING_BINDING_LEAF.BASIS)).isEqualTo("BARE_HEAD");
            assertThat(row.get(INTENT_ARGMAPPING_BINDING_LEAF.LEAF_KIND)).isEqualTo("ARGUMENT");
            assertThat(row.get(INTENT_ARGMAPPING_BINDING_LEAF.LEAF_ARGUMENT_NAME))
                .isEqualTo("inventoryId");
            assertThat(row.get(INTENT_ARGMAPPING_BINDING_LEAF.NODE_TYPE_REF))
                .isEqualTo("Inventory");
            assertThat(row.get(INTENT_ARGMAPPING_BINDING_LEAF.UNCONSUMED_SEGMENTS)).isZero();
        });
    }

    /**
     * A bare {@code @nodeId} with no {@code typeName:} resolves and carries a null reference, which
     * is the arm the missing-{@code typeName:} rejection reads. It is a resolution rather than a
     * silence because the leaf is known; what is missing is the node type it decodes against, and
     * there is no containing table at this position to infer one from.
     */
    @Test
    void aBareNodeIdWithNoTypeNameResolvesWithANullReference() {
        withSeededStore(GRAPH, dsl -> {
            seedField(dsl, GRAPH, "Mutation", "rentFilm");
            seedArgument(dsl, GRAPH, "Mutation", "rentFilm", "inventoryId", "ID");
            seedArgumentNodeId(dsl, GRAPH, "Mutation", "rentFilm", "inventoryId", null);
            pair(dsl, "Mutation", "rentFilm", 0, "pInventoryId", "inventoryId");

            var row = only(dsl);
            assertThat(row.get(INTENT_ARGMAPPING_BINDING_LEAF.DISPOSITION)).isEqualTo("RESOLVE");
            assertThat(row.get(INTENT_ARGMAPPING_BINDING_LEAF.NODE_TYPE_REF)).isNull();
        });
    }

    /**
     * A bare head naming a plain scalar argument contributes no row. This is the absence the
     * relation's meaning rests on: an ordinary binding needs nothing from this relation, so stating
     * it here would make every argMapping in the graph a row and take the meaning away from the ones
     * that matter.
     */
    @Test
    void aBareScalarArgumentHeadHasNoRow() {
        withSeededStore(GRAPH, dsl -> {
            seedField(dsl, GRAPH, "Mutation", "rentFilm");
            seedArgument(dsl, GRAPH, "Mutation", "rentFilm", "customerId", "Int");
            pair(dsl, "Mutation", "rentFilm", 0, "pCustomerId", "customerId");

            assertThat(rows(dsl)).isEmpty();
        });
    }

    // ===== The dotted argument-rooted path, which is the motivating shape =====

    /**
     * The motivating case: a dotted head descends into an input object and lands on a
     * {@code @nodeId} input field, leaving one trailing segment. One unconsumed segment is the
     * projection this item enables, and the count is what tells it from a typo.
     */
    @Test
    void aDottedPathOntoANodeIdInputFieldLeavesOneSegmentUnconsumed() {
        withRentFilmInput(dsl -> {
            seedFieldNodeId(dsl, GRAPH, "RentFilmInput", "inventoryId", "Inventory");
            pair(dsl, "Mutation", "rentFilm", 0, "pInventoryId", "input.inventoryId.inventory_id");

            var row = only(dsl);
            assertThat(row.get(INTENT_ARGMAPPING_BINDING_LEAF.DISPOSITION)).isEqualTo("RESOLVE");
            assertThat(row.get(INTENT_ARGMAPPING_BINDING_LEAF.BASIS)).isEqualTo("ARGUMENT_PATH");
            assertThat(row.get(INTENT_ARGMAPPING_BINDING_LEAF.LEAF_KIND)).isEqualTo("INPUT_FIELD");
            assertThat(row.get(INTENT_ARGMAPPING_BINDING_LEAF.LEAF_TYPE_NAME))
                .isEqualTo("RentFilmInput");
            assertThat(row.get(INTENT_ARGMAPPING_BINDING_LEAF.LEAF_FIELD_NAME))
                .isEqualTo("inventoryId");
            assertThat(row.get(INTENT_ARGMAPPING_BINDING_LEAF.LEAF_ARGUMENT_NAME)).isNull();
            assertThat(row.get(INTENT_ARGMAPPING_BINDING_LEAF.NODE_TYPE_REF))
                .isEqualTo("Inventory");
            assertThat(row.get(INTENT_ARGMAPPING_BINDING_LEAF.UNCONSUMED_SEGMENTS)).isEqualTo(1);
        });
    }

    /**
     * The same path with every segment consumed: the dotted binding of a {@code @nodeId} input field
     * with no key column named. This is the bare form at depth, and the zero is what the rejection
     * closing the silent hole keys on.
     */
    @Test
    void aDottedPathOntoANodeIdInputFieldWithNoKeyColumnConsumesEverything() {
        withRentFilmInput(dsl -> {
            seedFieldNodeId(dsl, GRAPH, "RentFilmInput", "inventoryId", "Inventory");
            pair(dsl, "Mutation", "rentFilm", 0, "pInventoryId", "input.inventoryId");

            var row = only(dsl);
            assertThat(row.get(INTENT_ARGMAPPING_BINDING_LEAF.BASIS)).isEqualTo("ARGUMENT_PATH");
            assertThat(row.get(INTENT_ARGMAPPING_BINDING_LEAF.UNCONSUMED_SEGMENTS)).isZero();
        });
    }

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
            assertThat(row.get(INTENT_ARGMAPPING_BINDING_LEAF.DISPOSITION)).isEqualTo("RESOLVE");
            assertThat(row.get(INTENT_ARGMAPPING_BINDING_LEAF.UNCONSUMED_SEGMENTS)).isEqualTo(2);
        });
    }

    /**
     * A path whose leaf is an input object rather than a scalar consumes every segment and carries no
     * {@code @nodeId}, so it has no row: the descent went as far as the author wrote and landed on an
     * ordinary nested binding.
     */
    @Test
    void aPathLandingOnAnInputObjectLeafHasNoRow() {
        withNestedInput(dsl -> {
            pair(dsl, "Mutation", "rentFilm", 0, "pNested", "input.nested");

            assertThat(rows(dsl)).isEmpty();
        });
    }

    /**
     * A segment naming no input field below a leaf that carries no {@code @nodeId} is the typo the
     * walk is silent on, and a stated row here rather than a second indistinguishable gap. The
     * descent stops at the argument, so the leaf columns say nothing and the basis names the cause.
     */
    @Test
    void aSegmentNamingNoInputFieldIsAStatedSilence() {
        withRentFilmInput(dsl -> {
            pair(dsl, "Mutation", "rentFilm", 0, "pNope", "input.nope");

            var row = only(dsl);
            assertThat(row.get(INTENT_ARGMAPPING_BINDING_LEAF.DISPOSITION)).isEqualTo("SILENT");
            assertThat(row.get(INTENT_ARGMAPPING_BINDING_LEAF.BASIS)).isEqualTo("UNRESOLVED_PATH");
            assertThat(row.get(INTENT_ARGMAPPING_BINDING_LEAF.LEAF_KIND)).isNull();
            assertThat(row.get(INTENT_ARGMAPPING_BINDING_LEAF.UNCONSUMED_SEGMENTS)).isNull();
        });
    }

    /**
     * A head naming no argument of the field is the same silence from the other end: nothing in
     * scope answers, so no leaf is reached at all.
     */
    @Test
    void aHeadNamingNoArgumentIsSilent() {
        withSeededStore(GRAPH, dsl -> {
            seedField(dsl, GRAPH, "Mutation", "rentFilm");
            seedArgument(dsl, GRAPH, "Mutation", "rentFilm", "input", "RentFilmInput");
            pair(dsl, "Mutation", "rentFilm", 0, "pNope", "notAnArgument");

            var row = only(dsl);
            assertThat(row.get(INTENT_ARGMAPPING_BINDING_LEAF.DISPOSITION)).isEqualTo("SILENT");
            assertThat(row.get(INTENT_ARGMAPPING_BINDING_LEAF.BASIS)).isEqualTo("UNRESOLVED_PATH");
        });
    }

    /**
     * A dotted path descending two levels resolves at its deepest matching prefix. Every prefix of
     * an occurrence path is its own row, so the pick has to be the deepest and not the first.
     */
    @Test
    void aTwoLevelDescentResolvesTheDeepestPrefix() {
        withNestedInput(dsl -> {
            seedFieldNodeId(dsl, GRAPH, "NestedInput", "inventoryId", "Inventory");
            pair(dsl, "Mutation", "rentFilm", 0, "pInventoryId",
                "input.nested.inventoryId.inventory_id");

            var row = only(dsl);
            assertThat(row.get(INTENT_ARGMAPPING_BINDING_LEAF.LEAF_TYPE_NAME))
                .isEqualTo("NestedInput");
            assertThat(row.get(INTENT_ARGMAPPING_BINDING_LEAF.LEAF_FIELD_NAME))
                .isEqualTo("inventoryId");
            assertThat(row.get(INTENT_ARGMAPPING_BINDING_LEAF.UNCONSUMED_SEGMENTS)).isEqualTo(1);
        });
    }

    // ===== The head is not always an argument =====

    /**
     * At an argument-site {@code @condition} the only slot in scope is the argument the directive
     * sits on, which is the walk's own rule. A head naming a different argument of the same field is
     * therefore a typo here even though the same spelling resolves at a field-site condition.
     */
    @Test
    void anArgumentSiteConditionResolvesOnlyItsOwnArgument() {
        withSeededStore(GRAPH, dsl -> {
            seedField(dsl, GRAPH, "Query", "films");
            seedArgument(dsl, GRAPH, "Query", "films", "other", "ID");
            seedArgumentNodeId(dsl, GRAPH, "Query", "films", "byActor", "Actor");
            seedArgumentConditionArgMappingPair(dsl, GRAPH, "Query", "films", "byActor", 0,
                "p", "byActor");
            seedArgumentPathSegments(dsl, GRAPH, "Query", "films", "byActor");
            seedArgumentConditionArgMappingPair(dsl, GRAPH, "Query", "films", "other", 0,
                "p", "byActor");
            seedArgumentPathSegments(dsl, GRAPH, "Query", "films", "byActor");

            assertThat(rowFor(dsl, "Query.films(byActor)")
                .orElseThrow().get(INTENT_ARGMAPPING_BINDING_LEAF.BASIS))
                .isEqualTo("BARE_HEAD");
            assertThat(rowFor(dsl, "Query.films(other)")
                .orElseThrow().get(INTENT_ARGMAPPING_BINDING_LEAF.BASIS))
                .as("the sibling argument is not in scope at this condition")
                .isEqualTo("UNRESOLVED_PATH");
        });
    }

    /**
     * At an input-field {@code @condition} the head names the input field the directive sits on, so a
     * bare head there resolves against the field relation and needs no occurrence path at all. That
     * independence is what lets this arm answer for an input type nothing reaches.
     */
    @Test
    void anInputFieldConditionResolvesItsOwnFieldWithNoOccurrencePath() {
        withSeededStore(GRAPH, dsl -> {
            seedDeclaredType(dsl, GRAPH, "RentFilmInput", "INPUT_OBJECT");
            seedField(dsl, GRAPH, "RentFilmInput", "inventoryId");
            seedFieldNodeId(dsl, GRAPH, "RentFilmInput", "inventoryId", "Inventory");
            seedFieldConditionArgMappingPair(dsl, GRAPH, "RentFilmInput", "inventoryId", 0,
                "p", "inventoryId");
            seedArgumentPathSegments(dsl, GRAPH, "RentFilmInput", "inventoryId", "inventoryId");

            var row = only(dsl);
            assertThat(row.get(INTENT_ARGMAPPING_BINDING_LEAF.SITE))
                .isEqualTo("INPUT_FIELD_CONDITION");
            assertThat(row.get(INTENT_ARGMAPPING_BINDING_LEAF.BASIS)).isEqualTo("BARE_HEAD");
            assertThat(row.get(INTENT_ARGMAPPING_BINDING_LEAF.LEAF_KIND)).isEqualTo("INPUT_FIELD");
            assertThat(row.get(INTENT_ARGMAPPING_BINDING_LEAF.UNCONSUMED_SEGMENTS)).isZero();
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
            seedFieldNodeId(dsl, GRAPH, "NestedInput", "inventoryId", "Inventory");
            seedFieldConditionArgMappingPair(dsl, GRAPH, "RentFilmInput", "nested", 0,
                "p", "nested.inventoryId.inventory_id");
            seedArgumentPathSegments(dsl, GRAPH, "RentFilmInput", "nested",
                "nested.inventoryId.inventory_id");

            var row = rowFor(dsl, "RentFilmInput.nested").orElseThrow();
            assertThat(row.get(INTENT_ARGMAPPING_BINDING_LEAF.SITE))
                .isEqualTo("INPUT_FIELD_CONDITION");
            assertThat(row.get(INTENT_ARGMAPPING_BINDING_LEAF.BASIS))
                .isEqualTo("INPUT_FIELD_PATH");
            assertThat(row.get(INTENT_ARGMAPPING_BINDING_LEAF.LEAF_TYPE_NAME))
                .isEqualTo("NestedInput");
            assertThat(row.get(INTENT_ARGMAPPING_BINDING_LEAF.LEAF_FIELD_NAME))
                .isEqualTo("inventoryId");
            assertThat(row.get(INTENT_ARGMAPPING_BINDING_LEAF.UNCONSUMED_SEGMENTS)).isEqualTo(1);
        });
    }

    /**
     * An input type no argument reaches has no occurrence path to descend, so a dotted head there is
     * a silence and not a resolution. A bare head at the same coordinate resolves regardless, which
     * is the pair of facts this case states together.
     */
    @Test
    void aDottedHeadOnAnUnreachedInputTypeIsSilent() {
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
            assertThat(row.get(INTENT_ARGMAPPING_BINDING_LEAF.DISPOSITION)).isEqualTo("SILENT");
            assertThat(row.get(INTENT_ARGMAPPING_BINDING_LEAF.BASIS))
                .isEqualTo("UNREACHED_INPUT_TYPE");
        });
    }

    // ===== A path-step condition resolves nothing at all =====

    /**
     * A path-step {@code @condition} resolves against an empty slot map, so nothing at that site can
     * resolve whatever it spells. Saying so keeps the emptiness a recorded fact rather than a
     * suspected bug in the view, and it is why those sites can only ever defer.
     */
    @Test
    void aPathStepConditionResolvesNoLeaf() {
        withSeededStore(GRAPH, dsl -> {
            seedField(dsl, GRAPH, "Film", "actors");
            seedArgument(dsl, GRAPH, "Film", "actors", "since", "String");
            seedArgumentNodeId(dsl, GRAPH, "Film", "actors", "since", "Inventory");
            seedFieldReferenceStepArgMappingPair(dsl, GRAPH, "Film", "actors", 0, 0, 0,
                "p", "since");
            seedArgumentPathSegments(dsl, GRAPH, "Film", "actors", "since");

            var row = only(dsl);
            assertThat(row.get(INTENT_ARGMAPPING_BINDING_LEAF.DISPOSITION)).isEqualTo("SILENT");
            assertThat(row.get(INTENT_ARGMAPPING_BINDING_LEAF.BASIS))
                .as("a head that would resolve anywhere else resolves nothing here")
                .isEqualTo("NO_SLOT_IN_SCOPE");
        });
    }

    // ===== The grain is the pair's own =====

    /**
     * Two applications of one repeatable directive each resolve their own path. Collapsing them to
     * the field coordinate would answer for one and drop the other silently, which is the one move
     * the nearest sibling view makes that this one must not.
     */
    @Test
    void twoRoutineApplicationsEachResolveTheirOwnPath() {
        withRentFilmInput(dsl -> {
            seedFieldNodeId(dsl, GRAPH, "RentFilmInput", "inventoryId", "Inventory");
            pair(dsl, "Mutation", "rentFilm", 0, "pInventoryId", "input.inventoryId.inventory_id");
            pair(dsl, "Mutation", "rentFilm", 1, "pInventoryId", "input.inventoryId");

            var rows = rows(dsl);
            assertThat(rows).hasSize(2);
            assertThat(rows.stream()
                .map(r -> r.get(INTENT_ARGMAPPING_BINDING_LEAF.UNCONSUMED_SEGMENTS)))
                .containsExactlyInAnyOrder(1, 0);
            assertThat(rows.stream().map(r -> r.get(INTENT_ARGMAPPING_BINDING_LEAF.USE_SITE)))
                .containsExactlyInAnyOrder("Mutation.rentFilm#0", "Mutation.rentFilm#1");
        });
    }

    /** Two pairs of one application each resolve their own path, position being part of the grain. */
    @Test
    void twoPairsOfOneApplicationEachResolveTheirOwnPath() {
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

    /** {@link #withRentFilmInput} with a second level under it, for the deepest-prefix cases. */
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

    /** The one row a single-pair fixture produces. */
    private static Record only(DSLContext dsl) {
        var all = rows(dsl);
        assertThat(all).hasSize(1);
        return all.getFirst();
    }
}
