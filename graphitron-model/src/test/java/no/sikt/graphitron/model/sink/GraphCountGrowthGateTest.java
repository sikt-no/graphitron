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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A stage's time does not grow with the other graphs in the store: one graph's work costs the same
 * whether it is the store's only graph or one of eight.
 *
 * <p>A shared store is how the tests run and how a reactor of several modules runs, and a stage
 * whose statement reads every graph's rows rather than its own pays for the others on every
 * capture: a recursive or windowed view the graph filter cannot reach into, or an upsert whose
 * match finds no index. A small store hides both. Each relation's time, written or read, is read
 * from {@code store_graph_write} and {@code store_graph_read}, so a stage that fetches its rule
 * into Java counts as surely as one that writes from it, for the first graph of a store, which was
 * alone when it was captured, and for the eighth, the same schema each time; the slower of the two
 * may be a generous multiple of the first plus a floor, timing ratios being noisy under a parallel
 * build, and is the best of two stores. A backstop for a stage that blows up rather than a
 * sensitive measure: an upsert matching on either of two keys grows less than the slack allows
 * here, and {@code UpsertKeyGateTest} reads the statements for that instead.
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
        "WRITE GRAPHITRON_CARRIER_DATA_FIELD", "grows with the graphs and sits near the bound, so "
            + "load tips it over; ends when \"The carrier data fields do not grow with the graphs "
            + "in the store\" lands");

    @Test
    @DisplayName("one graph's time per relation, written or read, does not grow with the other graphs")
    void aStagesTimeDoesNotGrowWithTheOtherGraphs() {
        var jooq = CatalogInput.of(TestRunContext.of());
        String sdl = ScaledSchemaFixture.scaledSdl(10);
        List<Map<String, Long>> alones = new ArrayList<>();
        List<Map<String, Long>> shareds = new ArrayList<>();
        for (int run = 0; run < 2; run++) {
            try (var store = CapturedStore.ownStoreOfCatalogWith(tmp.resolve("store" + run), "g1", sdl,
                    jooq, ClasspathCorpus.entries())) {
                for (int i = 2; i <= GRAPHS; i++) {
                    store.andCatalogGraphWith("g" + i, sdl, jooq, ClasspathCorpus.entries());
                }
                alones.add(times(store.dsl(), "g1"));
                shareds.add(times(store.dsl(), "g" + GRAPHS));
            }
        }
        Map<String, Long> alone = best(alones);
        Map<String, Long> shared = best(shareds);

        assertThat(alone).as("the fixture writes the stages this gate is about")
            .containsKeys("WRITE GRAPHITRON_FETCHER", "WRITE GRAPHITRON_TYPE_BACKING",
                "WRITE GRAPHITRON_NODE");
        List<String> grown = shared.entrySet().stream()
            .filter(e -> !KNOWN.containsKey(e.getKey()))
            .filter(e -> e.getValue() > RATIO * alone.getOrDefault(e.getKey(), 0L) + FLOOR_MS)
            .map(e -> e.getKey() + " " + alone.getOrDefault(e.getKey(), 0L) + " ms alone, "
                + e.getValue() + " ms as one of " + GRAPHS)
            .sorted()
            .collect(Collectors.toList());
        assertThat(grown)
            .as("a relation whose write or read covers every graph's rows: carry the graph into its "
                + "statement")
            .isEmpty();
    }

    /**
     * Each relation's best time over the runs. A read under the floor is not recorded, so a relation
     * a run lacks took no time in it.
     */
    private static Map<String, Long> best(List<Map<String, Long>> runs) {
        var best = new HashMap<String, Long>();
        runs.forEach(run -> run.keySet().forEach(relation -> best.put(relation,
            runs.stream().mapToLong(r -> r.getOrDefault(relation, 0L)).min().orElseThrow())));
        return best;
    }

    /**
     * Each relation's time in {@code graph}'s latest capture, written or read, keyed by kind and
     * relation and summed over the gatherers: a stage that fetches its rule into Java pays for it in
     * the read, so a read that grows is a stage that grows.
     */
    private static Map<String, Long> times(DSLContext dsl, String graph) {
        var times = new HashMap<String, Long>();
        WriteTimes.slowest(dsl, graph, Integer.MAX_VALUE).forEach(statement -> times.merge(
            statement.kind() + " " + statement.relation(), statement.millis(), Long::sum));
        return times;
    }
}
