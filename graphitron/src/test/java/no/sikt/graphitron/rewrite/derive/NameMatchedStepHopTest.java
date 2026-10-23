package no.sikt.graphitron.rewrite.derive;

import no.sikt.graphitron.model.test.CatalogInput;

import no.sikt.graphitron.model.test.CapturedStore;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.jooq.DSLContext;
import org.jooq.Record5;
import org.jooq.Result;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Locale;
import java.util.function.Consumer;

import static no.sikt.graphitron.common.configuration.TestConfiguration.testContext;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_REFERENCE_STEP_HOP;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The name-matched arm of {@code graphitron_field_reference_step_hop}: a {@code {table:}} element
 * departing a table-valued function's result, which declares no foreign key, is keyed by matching the
 * named table's primary-key columns against the result's columns by name.
 *
 * <p>Every case captures real SDL against the test catalog, a hand-seeded hop being free to describe
 * one the catalog cannot make. The fixtures use {@code films_for_actor}, whose result exposes
 * {@code film_id}, so the name-match to {@code film} succeeds.
 */
@PipelineTier
class NameMatchedStepHopTest {

    @TempDir
    Path tmp;

    /**
     * What the name-matched arm carries. No foreign key is involved, so the two foreign-key columns
     * are null rather than borrowed; the constraint
     * such a hop does key by is the arrival's primary key, which the arriving triple already reaches
     * through {@code sql_primary_key} and which carrying here would only duplicate.
     */
    @Test
    void aNameMatchedHopNamesNoForeignKey() {
        withCaptured(filmHop("[{table: \"film\"}]"), dsl -> {
            var rows = hops(dsl, "NAME_MATCH");
            assertThat(rows.map(r -> r.get(GRAPHITRON_FIELD_REFERENCE_STEP_HOP.FROM_TABLE))
                .stream().map(NameMatchedStepHopTest::lower).toList())
                .as("every function result in the graph's sources exposing film_id is a candidate")
                .containsExactlyInAnyOrder("films_for_actor", "films_for_actor_or_all");
            assertThat(rows).allSatisfy(row -> {
                assertThat(lower(row.get(GRAPHITRON_FIELD_REFERENCE_STEP_HOP.TO_TABLE)))
                    .isEqualTo("film");
                assertThat(row.get(GRAPHITRON_FIELD_REFERENCE_STEP_HOP.CONSTRAINT_NAME)).isNull();
                assertThat(row.get(GRAPHITRON_FIELD_REFERENCE_STEP_HOP.FK_ON_FROM)).isNull();
                assertThat(row.get(GRAPHITRON_FIELD_REFERENCE_STEP_HOP.KEY_MATCHED_BY)).isNull();
            });
        });
    }

    /**
     * The same element still resolves its foreign-key arm, which is what lets one written
     * {@code {table:}} element serve both parent shapes. The two arms cannot produce one row: a
     * function result declares no foreign key for the other arm to discover.
     */
    @Test
    void theForeignKeyArmOfTheSameElementIsUntouched() {
        withCaptured(filmHop("[{table: \"film\"}]"), dsl ->
            assertThat(hops(dsl, "TABLE"))
                .as("film's own foreign keys, discovered as before")
                .isNotEmpty());
    }

    // ===== Helpers =====

    /**
     * The root routine-then-hops fixture: {@code films_for_actor} in the FROM and one
     * {@code @reference} application carrying {@code path}.
     */
    private static String filmHop(String path) {
        return """
            type Film @table(name: "film") { title: String }
            type Query {
              recentFilms(actorId: Int!, minLength: Int!): [Film!]!
                @routine(name: "films_for_actor",
                         argMapping: "pActorId: actorId, pMinLength: minLength")
                @reference(path: %s)
            }
            """.formatted(path);
    }

    private static Result<Record5<String, String, String, Boolean, String>>
            hops(DSLContext dsl, String via) {
        return dsl.select(GRAPHITRON_FIELD_REFERENCE_STEP_HOP.CONSTRAINT_NAME,
            GRAPHITRON_FIELD_REFERENCE_STEP_HOP.FROM_TABLE,
            GRAPHITRON_FIELD_REFERENCE_STEP_HOP.TO_TABLE,
            GRAPHITRON_FIELD_REFERENCE_STEP_HOP.FK_ON_FROM,
            GRAPHITRON_FIELD_REFERENCE_STEP_HOP.KEY_MATCHED_BY)
            .from(GRAPHITRON_FIELD_REFERENCE_STEP_HOP)
            .where(GRAPHITRON_FIELD_REFERENCE_STEP_HOP.GRAPH_NAME.eq(CapturedStore.GRAPH))
            .and(GRAPHITRON_FIELD_REFERENCE_STEP_HOP.VIA.eq(via))
            .orderBy(GRAPHITRON_FIELD_REFERENCE_STEP_HOP.POSITION,
                GRAPHITRON_FIELD_REFERENCE_STEP_HOP.FROM_TABLE,
                GRAPHITRON_FIELD_REFERENCE_STEP_HOP.TO_TABLE)
            .fetch();
    }

    private static String lower(String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }

    private void withCaptured(String sdl, Consumer<DSLContext> body) {
        var ctx = testContext();
        try (var store = CapturedStore.ofCatalog(tmp, sdl,
                CatalogInput.of(ctx))) {
            body.accept(store.dsl());
        }
    }
}
