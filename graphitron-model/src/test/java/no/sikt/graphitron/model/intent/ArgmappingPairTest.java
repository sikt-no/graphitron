package no.sikt.graphitron.model.intent;

import org.jooq.DSLContext;
import org.jooq.Record;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_ARG_MAPPING_PAIR;
import static no.sikt.graphitron.model.test.SeededStore.derive;
import static no.sikt.graphitron.model.test.SeededStore.seedArgumentConditionArgMappingPair;
import static no.sikt.graphitron.model.test.SeededStore.seedArgumentReferenceStepArgMappingPair;
import static no.sikt.graphitron.model.test.SeededStore.seedDeclaredType;
import static no.sikt.graphitron.model.test.SeededStore.seedField;
import static no.sikt.graphitron.model.test.SeededStore.seedFieldConditionArgMappingPair;
import static no.sikt.graphitron.model.test.SeededStore.seedFieldReferenceStepArgMappingPair;
import static no.sikt.graphitron.model.test.SeededStore.seedGraph;
import static no.sikt.graphitron.model.test.SeededStore.seedReferenceForStepArgMappingPair;
import static no.sikt.graphitron.model.test.SeededStore.seedRoutineArgMappingPair;
import static no.sikt.graphitron.model.test.SeededStore.seedServiceArgMappingPair;
import static no.sikt.graphitron.model.test.SeededStore.withSeededStore;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What {@code graphitron_arg_mapping_pair} holds: every {@code argMapping} pair any directive
 * spells, in one relation with a {@code site} literal naming which kind of site spelled a row.
 *
 * <p>These cases once pinned a union's correctness, the relation having been a widening over eight
 * per-site tables. Those are gone and capture writes the widened shape directly, so what is left to
 * pin is what survived the collapse and is still a rule rather than a projection: that every site
 * value is reachable, that each row carries the extra key columns its own kind has and NULL where
 * it has none, and that the serialized use-site key tells two applications of one repeatable
 * directive apart. A site no case reaches is a site nothing checks.
 *
 * <p>The last case pins the property every later reader depends on. A consumer holding
 * {@code (site, use_site, position)} can join back and recover the arm's own components, which is
 * what makes the serialized key a key rather than a message string. Nothing parses it.
 */
class ArgmappingPairTest {

    private static final String GRAPH = "g";

    private static final List<String> EVERY_SITE = List.of(
        "ROUTINE", "SERVICE", "FIELD_CONDITION", "INPUT_FIELD_CONDITION", "ARGUMENT_CONDITION",
        "FIELD_REFERENCE_STEP", "ARGUMENT_REFERENCE_STEP", "REFERENCE_FOR_STEP");

    // ===== One arm at a time =====

    /** The repeatable arm carries its application's ordinal, and the ordinal is in the key. */
    @Test
    void aRoutinePairCarriesItsApplicationsOrdinal() {
        withSeededStore(GRAPH, dsl -> {
            seedField(dsl, GRAPH, "Mutation", "rentFilm");
            seedRoutineArgMappingPair(dsl, GRAPH, "Mutation", "rentFilm", 0, 0,
                "pInventoryId", "input.inventoryId");

            var row = only(dsl);
            assertThat(row.get(GRAPHITRON_ARG_MAPPING_PAIR.SITE)).isEqualTo("ROUTINE");
            assertThat(row.get(GRAPHITRON_ARG_MAPPING_PAIR.USE_SITE)).isEqualTo("Mutation.rentFilm#0");
            assertThat(row.get(GRAPHITRON_ARG_MAPPING_PAIR.ORDINAL)).isZero();
            assertThat(row.get(GRAPHITRON_ARG_MAPPING_PAIR.ARGUMENT_NAME)).isNull();
            assertThat(row.get(GRAPHITRON_ARG_MAPPING_PAIR.STEP_POSITION)).isNull();
            assertThat(row.get(GRAPHITRON_ARG_MAPPING_PAIR.PARAM_NAME)).isEqualTo("pInventoryId");
            assertThat(row.get(GRAPHITRON_ARG_MAPPING_PAIR.ARGUMENT_PATH)).isEqualTo("input.inventoryId");
        });
    }

    /** The narrowest arm: a field coordinate and nothing else, so all three extras are absent. */
    @Test
    void aServicePairCarriesNoOrdinalAndNoStep() {
        withSeededStore(GRAPH, dsl -> {
            seedField(dsl, GRAPH, "Query", "film");
            seedServiceArgMappingPair(dsl, GRAPH, "Query", "film", 0, "id", "filmId");

            var row = only(dsl);
            assertThat(row.get(GRAPHITRON_ARG_MAPPING_PAIR.SITE)).isEqualTo("SERVICE");
            assertThat(row.get(GRAPHITRON_ARG_MAPPING_PAIR.USE_SITE)).isEqualTo("Query.film");
            assertThat(row.get(GRAPHITRON_ARG_MAPPING_PAIR.ORDINAL))
                .as("@service is not repeatable, so there is no ordinal to carry")
                .isNull();
            assertThat(row.get(GRAPHITRON_ARG_MAPPING_PAIR.STEP_POSITION)).isNull();
            assertThat(row.get(GRAPHITRON_ARG_MAPPING_PAIR.ARGUMENT_NAME)).isNull();
        });
    }

    /** The argument-grain arm carries the argument, and the key names it in parentheses. */
    @Test
    void anArgumentConditionPairNamesItsArgumentInTheKey() {
        withSeededStore(GRAPH, dsl -> {
            seedField(dsl, GRAPH, "Query", "films");
            seedArgumentConditionArgMappingPair(dsl, GRAPH, "Query", "films", "titleLike", 0,
                "pattern", "titleLike");

            var row = only(dsl);
            assertThat(row.get(GRAPHITRON_ARG_MAPPING_PAIR.SITE)).isEqualTo("ARGUMENT_CONDITION");
            assertThat(row.get(GRAPHITRON_ARG_MAPPING_PAIR.USE_SITE))
                .isEqualTo("Query.films(titleLike)");
            assertThat(row.get(GRAPHITRON_ARG_MAPPING_PAIR.ARGUMENT_NAME)).isEqualTo("titleLike");
            assertThat(row.get(GRAPHITRON_ARG_MAPPING_PAIR.ORDINAL)).isNull();
        });
    }

    /** A step arm carries both an ordinal and a step position, the widest shape but the argument. */
    @Test
    void aFieldReferenceStepPairCarriesBothAnOrdinalAndAStep() {
        withSeededStore(GRAPH, dsl -> {
            seedField(dsl, GRAPH, "Film", "actors");
            seedFieldReferenceStepArgMappingPair(dsl, GRAPH, "Film", "actors", 1, 2, 0,
                "cutoff", "since");

            var row = only(dsl);
            assertThat(row.get(GRAPHITRON_ARG_MAPPING_PAIR.SITE)).isEqualTo("FIELD_REFERENCE_STEP");
            assertThat(row.get(GRAPHITRON_ARG_MAPPING_PAIR.USE_SITE)).isEqualTo("Film.actors#1[2]");
            assertThat(row.get(GRAPHITRON_ARG_MAPPING_PAIR.ORDINAL)).isEqualTo(1);
            assertThat(row.get(GRAPHITRON_ARG_MAPPING_PAIR.STEP_POSITION)).isEqualTo(2);
            assertThat(row.get(GRAPHITRON_ARG_MAPPING_PAIR.ARGUMENT_NAME)).isNull();
        });
    }

    /** The widest kind: an argument, an ordinal and a step, all three in the key. */
    @Test
    void anArgumentReferenceStepPairCarriesAllThreeExtras() {
        withSeededStore(GRAPH, dsl -> {
            seedField(dsl, GRAPH, "Query", "films");
            seedArgumentReferenceStepArgMappingPair(dsl, GRAPH, "Query", "films", "byActor", 0, 1, 0,
                "actorId", "byActor");

            var row = only(dsl);
            assertThat(row.get(GRAPHITRON_ARG_MAPPING_PAIR.SITE)).isEqualTo("ARGUMENT_REFERENCE_STEP");
            assertThat(row.get(GRAPHITRON_ARG_MAPPING_PAIR.USE_SITE))
                .isEqualTo("Query.films(byActor)#0[1]");
            assertThat(row.get(GRAPHITRON_ARG_MAPPING_PAIR.ARGUMENT_NAME)).isEqualTo("byActor");
            assertThat(row.get(GRAPHITRON_ARG_MAPPING_PAIR.ORDINAL)).isZero();
            assertThat(row.get(GRAPHITRON_ARG_MAPPING_PAIR.STEP_POSITION)).isEqualTo(1);
        });
    }

    /**
     * The {@code @referenceFor} arm serializes to the same key shape as the field-reference step
     * arm, and {@code site} is what tells them apart. Two arms of one shape is exactly why the
     * discriminator is part of the grain rather than a decoration on it.
     */
    @Test
    void theReferenceForArmSharesAKeyShapeAndIsToldApartBySite() {
        withSeededStore(GRAPH, dsl -> {
            seedField(dsl, GRAPH, "Film", "actors");
            seedFieldReferenceStepArgMappingPair(dsl, GRAPH, "Film", "actors", 0, 0, 0,
                "a", "one");
            seedReferenceForStepArgMappingPair(dsl, GRAPH, "Film", "actors", 0, 0, 0,
                "b", "two");

            var rows = rows(dsl);
            assertThat(rows).hasSize(2);
            assertThat(rows.stream().map(r -> r.get(GRAPHITRON_ARG_MAPPING_PAIR.USE_SITE)).distinct())
                .as("the two arms serialize one coordinate the same way")
                .containsExactly("Film.actors#0[0]");
            assertThat(rows.stream().map(r -> r.get(GRAPHITRON_ARG_MAPPING_PAIR.SITE)))
                .containsExactlyInAnyOrder("FIELD_REFERENCE_STEP", "REFERENCE_FOR_STEP");
        });
    }

    // ===== The shared coordinate splits by the owning type's kind =====

    /**
     * A {@code @condition} on an output field is the {@code FIELD_CONDITION} site. The relation is
     * shared, so the kind of the type the pair row sits on is the whole discriminator.
     */
    @Test
    void aConditionOnAnOutputFieldIsTheOutputFieldSite() {
        withSeededStore(GRAPH, dsl -> {
            seedDeclaredType(dsl, GRAPH, "Film", "OBJECT");
            seedField(dsl, GRAPH, "Film", "actors");
            seedFieldConditionArgMappingPair(dsl, GRAPH, "Film", "actors", 0, "p", "since");

            var row = only(dsl);
            assertThat(row.get(GRAPHITRON_ARG_MAPPING_PAIR.SITE)).isEqualTo("FIELD_CONDITION");
            assertThat(row.get(GRAPHITRON_ARG_MAPPING_PAIR.USE_SITE)).isEqualTo("Film.actors");
        });
    }

    /**
     * The same relation on an input object type is the {@code INPUT_FIELD_CONDITION} site, whose
     * head names an input field rather than an argument and whose emitter is a different one. Two
     * site values for one relation is what this case exists to pin.
     */
    @Test
    void aConditionOnAnInputFieldIsTheInputFieldSite() {
        withSeededStore(GRAPH, dsl -> {
            seedDeclaredType(dsl, GRAPH, "FilmFilter", "INPUT_OBJECT");
            seedField(dsl, GRAPH, "FilmFilter", "titleLike");
            seedFieldConditionArgMappingPair(dsl, GRAPH, "FilmFilter", "titleLike", 0,
                "p", "titleLike");

            var row = only(dsl);
            assertThat(row.get(GRAPHITRON_ARG_MAPPING_PAIR.SITE)).isEqualTo("INPUT_FIELD_CONDITION");
            assertThat(row.get(GRAPHITRON_ARG_MAPPING_PAIR.USE_SITE)).isEqualTo("FilmFilter.titleLike");
        });
    }

    // ===== The grain is the pair's own =====

    /**
     * Two applications of one repeatable directive on one coordinate stay two use sites. Collapsing
     * them to one row per field coordinate is the one move the nearest sibling view makes that this
     * relation must not: it would resolve one application's paths and drop the other's in silence.
     */
    @Test
    void twoApplicationsOnOneCoordinateStayTwoUseSites() {
        withSeededStore(GRAPH, dsl -> {
            seedField(dsl, GRAPH, "Mutation", "rentFilm");
            seedRoutineArgMappingPair(dsl, GRAPH, "Mutation", "rentFilm", 0, 0, "p", "one");
            seedRoutineArgMappingPair(dsl, GRAPH, "Mutation", "rentFilm", 1, 0, "p", "two");

            assertThat(rows(dsl).stream().map(r -> r.get(GRAPHITRON_ARG_MAPPING_PAIR.USE_SITE)))
                .containsExactlyInAnyOrder("Mutation.rentFilm#0", "Mutation.rentFilm#1");
        });
    }

    /**
     * A duplicate parameter at two positions survives, position being part of the grain. The base
     * relations key on it so an author's duplicate reaches the duplicate detection, and a
     * normalisation that dropped it would take that detection's input away.
     */
    @Test
    void aDuplicateParameterAtTwoPositionsSurvives() {
        withSeededStore(GRAPH, dsl -> {
            seedField(dsl, GRAPH, "Query", "film");
            seedServiceArgMappingPair(dsl, GRAPH, "Query", "film", 0, "id", "one");
            seedServiceArgMappingPair(dsl, GRAPH, "Query", "film", 1, "id", "two");

            assertThat(rows(dsl)).hasSize(2);
            assertThat(rows(dsl).stream().map(r -> r.get(GRAPHITRON_ARG_MAPPING_PAIR.POSITION)))
                .containsExactlyInAnyOrder(0, 1);
        });
    }

    /** The graph partition holds: a sibling graph's pairs are not this graph's rows. */
    @Test
    void aSiblingGraphsPairsAreNotThisGraphsRows() {
        withSeededStore(GRAPH, dsl -> {
            seedGraph(dsl, "other");
            seedField(dsl, "other", "Query", "film");
            seedServiceArgMappingPair(dsl, "other", "Query", "film", 0, "id", "filmId");

            assertThat(rows(dsl)).isEmpty();
        });
    }

    // ===== Coverage, and the property every reader depends on =====

    /**
     * All site values are reachable from one fixture. The site vocabulary is closed over
     * relations of differing key arity, so an arm no case reaches is an arm nothing checks; this is
     * the case that makes the vocabulary's closure a claim rather than a comment.
     */
    @Test
    void everySiteValueIsReachable() {
        withEveryArm(dsl ->
            assertThat(rows(dsl).stream().map(r -> r.get(GRAPHITRON_ARG_MAPPING_PAIR.SITE)).distinct())
                .containsExactlyInAnyOrderElementsOf(EVERY_SITE));
    }

    /**
     * The join-back property: {@code (site, use_site, position)} identifies one row, so a consumer
     * holding the serialized key recovers the arm's own components by joining rather than by
     * parsing. Without this the key would be message text and every reader needing an ordinal would
     * re-spell the union.
     */
    @Test
    void theSiteAndUseSiteAndPositionIdentifyOneRow() {
        withEveryArm(dsl -> {
            var rows = rows(dsl);
            assertThat(rows.stream()
                .map(r -> List.of(r.get(GRAPHITRON_ARG_MAPPING_PAIR.SITE),
                    r.get(GRAPHITRON_ARG_MAPPING_PAIR.USE_SITE),
                    r.get(GRAPHITRON_ARG_MAPPING_PAIR.POSITION)))
                .distinct())
                .as("the grain is site plus the use-site key plus the position within the list")
                .hasSize(rows.size());

            var recovered = dsl.select(GRAPHITRON_ARG_MAPPING_PAIR.ORDINAL,
                    GRAPHITRON_ARG_MAPPING_PAIR.STEP_POSITION, GRAPHITRON_ARG_MAPPING_PAIR.ARGUMENT_NAME)
                .from(GRAPHITRON_ARG_MAPPING_PAIR)
                .where(GRAPHITRON_ARG_MAPPING_PAIR.GRAPH_NAME.eq(GRAPH))
                .and(GRAPHITRON_ARG_MAPPING_PAIR.SITE.eq("ARGUMENT_REFERENCE_STEP"))
                .and(GRAPHITRON_ARG_MAPPING_PAIR.USE_SITE.eq("Query.films(byActor)#0[1]"))
                .and(GRAPHITRON_ARG_MAPPING_PAIR.POSITION.eq(0))
                .fetchSingle();
            assertThat(recovered.value1()).isZero();
            assertThat(recovered.value2()).isEqualTo(1);
            assertThat(recovered.value3()).isEqualTo("byActor");
        });
    }

    // ===== Fixtures =====

    /** One pair at each site, which is the fixture the coverage cases read. */
    private static void withEveryArm(Consumer<DSLContext> body) {
        withSeededStore(GRAPH, dsl -> {
            seedDeclaredType(dsl, GRAPH, "Film", "OBJECT");
            seedDeclaredType(dsl, GRAPH, "FilmFilter", "INPUT_OBJECT");
            seedField(dsl, GRAPH, "Mutation", "rentFilm");
            seedField(dsl, GRAPH, "Query", "film");
            seedField(dsl, GRAPH, "Query", "films");
            seedField(dsl, GRAPH, "Film", "actors");
            seedField(dsl, GRAPH, "FilmFilter", "titleLike");

            seedRoutineArgMappingPair(dsl, GRAPH, "Mutation", "rentFilm", 0, 0, "p", "input");
            seedServiceArgMappingPair(dsl, GRAPH, "Query", "film", 0, "id", "filmId");
            seedFieldConditionArgMappingPair(dsl, GRAPH, "Film", "actors", 0, "p", "since");
            seedFieldConditionArgMappingPair(dsl, GRAPH, "FilmFilter", "titleLike", 0, "p", "q");
            seedArgumentConditionArgMappingPair(dsl, GRAPH, "Query", "films", "titleLike", 0,
                "p", "titleLike");
            seedFieldReferenceStepArgMappingPair(dsl, GRAPH, "Film", "actors", 0, 0, 0, "a", "one");
            seedArgumentReferenceStepArgMappingPair(dsl, GRAPH, "Query", "films", "byActor", 0, 1, 0,
                "actorId", "byActor");
            seedReferenceForStepArgMappingPair(dsl, GRAPH, "Film", "actors", 0, 0, 0, "b", "two");

            body.accept(dsl);
        });
    }

    // ===== Reads =====

    /** Every row of the graph under assertion. */
    private static List<Record> rows(DSLContext dsl) {
        derive(dsl);
        return dsl.select(GRAPHITRON_ARG_MAPPING_PAIR.fields())
            .from(GRAPHITRON_ARG_MAPPING_PAIR)
            .where(GRAPHITRON_ARG_MAPPING_PAIR.GRAPH_NAME.eq(GRAPH))
            .fetch()
            .stream()
            .map(Record.class::cast)
            .toList();
    }

    /** The one row a single-pair fixture produces. */
    private static Record only(DSLContext dsl) {
        var rows = rows(dsl);
        assertThat(rows).hasSize(1);
        return Optional.of(rows.getFirst()).orElseThrow();
    }
}
