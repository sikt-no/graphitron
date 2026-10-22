package no.sikt.graphitron.model.sink;

import no.sikt.graphitron.model.test.CapturedStore;
import no.sikt.graphitron.model.test.CatalogInput;
import no.sikt.graphitron.model.test.ClasspathCorpus;
import no.sikt.graphitron.model.test.ScaledSchemaFixture;
import no.sikt.graphitron.model.test.TestRunContext;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static no.sikt.graphitron.model.Tables.STORE_GRAPH_WRITE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.jooq.impl.DSL.max;
import static org.jooq.impl.DSL.sum;

/**
 * A stage's time does not grow with the other graphs in the store: one graph's work costs the same
 * whether it is the store's only graph or one of eight.
 *
 * <p>A shared store is how the tests run and how a reactor of several modules runs, and a stage
 * whose statement reads every graph's rows rather than its own pays for the others on every
 * capture: a recursive or windowed view the graph filter cannot reach into, or an upsert whose
 * match finds no index. A small store hides both. Each table's time is read from
 * {@code store_graph_write}, for the first graph of a store, which was alone when it was captured,
 * and for the eighth, the same schema each time; the slower of the two may be a generous multiple
 * of the first plus a floor, timing ratios being noisy under a parallel build, and is the best of
 * two stores. A backstop for a stage that blows up rather than a sensitive measure: an upsert
 * matching on either of two keys grows less than the slack allows here, and
 * {@code UpsertKeyGateTest} reads the statements for that instead.
 */
class GraphCountGrowthGateTest {

    @TempDir
    Path tmp;

    private static final int GRAPHS = 8;
    private static final double RATIO = 4.0;
    private static final long FLOOR_MS = 40;

    /**
     * Tables known to grow past the slack with the graphs in a store, each with why and the node that
     * ends it. Not a ratchet: a known table can dip under the bound on a quiet run, so one that stops
     * growing comes off by hand.
     */
    private static final Map<String, String> KNOWN = Map.of(
        "GRAPHITRON_RESOLVED_TYPE_BINDING", "reads intent_field_path_node, whose recursive walk "
            + "evaluates every graph's paths; goes when \"Nothing resolves a path but the walk\" lands");

    @Test
    @DisplayName("one graph's write time per table does not grow with the other graphs in the store")
    void aStagesTimeDoesNotGrowWithTheOtherGraphs() {
        var jooq = CatalogInput.of(TestRunContext.of());
        String sdl = ScaledSchemaFixture.scaledSdl(10);
        Map<String, Long> alone = new HashMap<>();
        Map<String, Long> shared = new HashMap<>();
        for (int run = 0; run < 2; run++) {
            try (var store = CapturedStore.ownStoreOfCatalogWith(tmp.resolve("store" + run), "g1", sdl,
                    jooq, ClasspathCorpus.entries())) {
                for (int i = 2; i <= GRAPHS; i++) {
                    store.andCatalogGraphWith("g" + i, sdl, jooq, ClasspathCorpus.entries());
                }
                times(store.dsl(), "g1").forEach((table, ms) -> alone.merge(table, ms, Math::min));
                times(store.dsl(), "g" + GRAPHS).forEach((table, ms) -> shared.merge(table, ms, Math::min));
            }
        }

        assertThat(alone).as("the fixture writes the stages this gate is about")
            .containsKeys("GRAPHITRON_FETCHER", "GRAPHITRON_TYPE_BACKING", "GRAPHITRON_NODE");
        List<String> grown = shared.entrySet().stream()
            .filter(e -> !KNOWN.containsKey(e.getKey()))
            .filter(e -> e.getValue() > RATIO * alone.getOrDefault(e.getKey(), 0L) + FLOOR_MS)
            .map(e -> e.getKey() + " " + alone.getOrDefault(e.getKey(), 0L) + " ms alone, "
                + e.getValue() + " ms as one of " + GRAPHS)
            .sorted()
            .collect(Collectors.toList());
        assertThat(grown)
            .as("a table whose write reads every graph's rows: carry the graph into its statement")
            .isEmpty();
    }

    /** Each table's write time in {@code graph}'s latest capture, summed over the gatherers writing it. */
    private static Map<String, Long> times(DSLContext dsl, String graph) {
        var w = STORE_GRAPH_WRITE;
        return dsl.select(w.TABLE_NAME, sum(w.TIME_SPENT_MS)).from(w)
            .where(w.GRAPH_NAME.eq(graph))
            .and(w.CAPTURED_AT.eq(dsl.select(max(w.CAPTURED_AT)).from(w).where(w.GRAPH_NAME.eq(graph))))
            .groupBy(w.TABLE_NAME)
            .fetchMap(r -> r.value1(), r -> r.value2().longValue());
    }
}
