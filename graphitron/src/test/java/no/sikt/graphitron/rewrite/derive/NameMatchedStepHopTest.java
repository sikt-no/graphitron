package no.sikt.graphitron.rewrite.derive;

import no.sikt.graphitron.model.test.CatalogInput;

import no.sikt.graphitron.model.test.CapturedStore;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import static no.sikt.graphitron.common.configuration.TestConfiguration.testContext;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_JOIN_PATH_STEP;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_JOIN_PATH_STEP_INFERRED_KEY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_JOIN_PATH_STEP_KEY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_JOIN_PATH_STEP_MATCHED_KEY;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The name-matched step of a join path: a {@code {table:}} element departing a table-valued
 * function's result, which declares no foreign key, is keyed by matching the named table's
 * primary-key columns against the result's columns by name.
 *
 * <p>Every case captures real SDL against the test catalog, a hand-seeded step being free to describe
 * one the catalog cannot make. The fixture uses {@code films_for_actor}, whose result exposes
 * {@code film_id}, so the name-match to {@code film} succeeds.
 */
@PipelineTier
class NameMatchedStepHopTest {

    @TempDir
    Path tmp;

    /**
     * The step leaves the function the root's {@code @routine} returns and reaches {@code film} as a
     * name-matched step, riding no foreign key: no key subtype holds a row for it.
     */
    @Test
    void aNameMatchedStepRidesNoForeignKey() {
        withCaptured(filmHop("[{table: \"film\"}]"), dsl -> {
            var s = GRAPHITRON_JOIN_PATH_STEP;
            var mk = GRAPHITRON_JOIN_PATH_STEP_MATCHED_KEY;
            List<String> steps = dsl.select(s.FROM_TABLE, s.TO_TABLE)
                .from(s)
                .join(mk).on(mk.GRAPH_NAME.eq(s.GRAPH_NAME), mk.COORDINATE.eq(s.COORDINATE),
                    mk.TARGET_TYPE.eq(s.TARGET_TYPE), mk.POSITION.eq(s.POSITION))
                .where(s.GRAPH_NAME.eq(CapturedStore.GRAPH), s.COORDINATE.eq("Query.recentFilms"))
                .fetch(r -> lower(r.value1()) + "->" + lower(r.value2()));
            assertThat(steps).containsExactly("films_for_actor->film");
            assertThat(dsl.fetchCount(GRAPHITRON_JOIN_PATH_STEP_KEY,
                    GRAPHITRON_JOIN_PATH_STEP_KEY.COORDINATE.eq("Query.recentFilms")))
                .isZero();
            assertThat(dsl.fetchCount(GRAPHITRON_JOIN_PATH_STEP_INFERRED_KEY,
                    GRAPHITRON_JOIN_PATH_STEP_INFERRED_KEY.COORDINATE.eq("Query.recentFilms")))
                .isZero();
        });
    }

    // ===== Helpers =====

    /**
     * The root routine-then-steps fixture: {@code films_for_actor} in the FROM and one
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
