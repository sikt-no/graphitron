package no.sikt.graphitron.model.test;

import no.sikt.graphitron.model.catalog.GraphPartition;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static no.sikt.graphitron.model.Tables.STORE_GRAPH;
import static no.sikt.graphitron.model.test.SeededStore.seedGraph;
import static no.sikt.graphitron.model.test.SeededStore.withSeededStore;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The funnel's own store discipline: that two bodies on one thread share a store, that the second
 * one cannot see the first one's rows or statistics, and that the module boots a store per thread
 * rather than per case.
 *
 * <p>The last of those is the mechanism's whole claim, so a test states it rather than a comment.
 * It is an equality rather than the literal four, and that is not caution: {@code parallelism=4}
 * sizes a {@code ForkJoinPool}, which adds compensation threads when a task blocks, and this
 * module's own run boots eight stores on eight threads rather than four on four. So
 * {@code boots == 4} would fail here today while one boot per booting thread holds, and the
 * equality is the stronger statement besides, failing the moment a future helper opens a store per
 * case again.
 */
class ThreadConfinedStoreTest {

    @Test
    @DisplayName("two bodies on one thread get the same store")
    void sharesOneStorePerThread() {
        List<DSLContext> seen = new ArrayList<>();
        withSeededStore(seen::add);
        withSeededStore(seen::add);
        assertThat(seen).hasSize(2);
        assertThat(seen.get(1)).isSameAs(seen.getFirst());
    }

    @Test
    @DisplayName("a body never sees the previous body's rows")
    void clearsRowsBetweenBodies() {
        withSeededStore(dsl -> seedGraph(dsl, "leaked"));
        withSeededStore(dsl -> assertThat(dsl.fetchCount(STORE_GRAPH)).isZero());
    }

    @Test
    @DisplayName("the store a body gets still holds the registry rows a boot puts there")
    void keepsTheStoreBootable() {
        withSeededStore(dsl -> {
            seedGraph(dsl, "derivable");
            SeededStore.derive(dsl);
        });
        withSeededStore(dsl -> {
            seedGraph(dsl, "derivable");
            SeededStore.derive(dsl);
        });
    }

    /**
     * The other half of a booted state, and the half no row count can see: a body that analyses
     * leaves selectivities behind on the tables the clear truncates and on the populated
     * {@code meta_} tables it does not, and on an empty graph-keyed table the analysis also drops
     * the partition declaration. The next body has to see none of that. Compared with a created
     * store's snapshot rather than with the two values spelled out, because "what a created store
     * carries" is the claim, and the drift is asserted first so the comparison is never vacuous.
     */
    @Test
    @DisplayName("a body gets a created store's statistics, whatever the previous body analysed")
    void resetsStatisticsBetweenBodies() {
        Map<String, Integer> created;
        try (var store = FactStores.inMemory()) {
            created = selectivities(store.dsl());
        }
        withSeededStore(dsl -> {
            seedGraph(dsl, "analysed");
            dsl.execute("ANALYZE");
            assertThat(drift(selectivities(dsl), created).keySet())
                .as("columns the analysis moved off a created store's values: on a table the clear"
                    + " truncates, on a meta_ table it keeps, and a partition column")
                .anyMatch(column -> column.startsWith("STORE_GRAPH."))
                .anyMatch(column -> column.startsWith("META_"))
                .anyMatch(column -> column.endsWith("." + GraphPartition.COLUMN));
        });
        withSeededStore(dsl -> {
            Map<String, Integer> borrowed = selectivities(dsl);
            assertThat(borrowed.keySet())
                .as("base-table columns, against a created store's")
                .isEqualTo(created.keySet());
            assertThat(drift(borrowed, created))
                .as("columns whose selectivity differs from a created store's, each with what it"
                    + " reports instead")
                .isEmpty();
        });
    }

    /** The columns of {@code seen} reporting something other than {@code created} does. */
    private static Map<String, Integer> drift(Map<String, Integer> seen, Map<String, Integer> created) {
        var drift = new TreeMap<>(seen);
        drift.entrySet().removeIf(column -> column.getValue().equals(created.get(column.getKey())));
        return drift;
    }

    /** Every base-table column's selectivity, keyed {@code TABLE.COLUMN}. */
    private static Map<String, Integer> selectivities(DSLContext dsl) {
        Set<String> baseTables = Set.copyOf(ThreadConfinedStore.baseTables(dsl));
        var selectivities = new TreeMap<String, Integer>();
        dsl.fetch("""
            SELECT TABLE_NAME, COLUMN_NAME, SELECTIVITY FROM INFORMATION_SCHEMA.COLUMNS
            WHERE TABLE_SCHEMA = 'PUBLIC'
            """).forEach(row -> {
                if (baseTables.contains(row.get(0, String.class))) {
                    selectivities.put(row.get(0) + "." + row.get(1), row.get(2, Integer.class));
                }
            });
        return selectivities;
    }

    @Test
    @DisplayName("nesting throws rather than clearing the outer body's rows")
    void refusesReentry() {
        assertThatThrownBy(() -> withSeededStore(outer -> withSeededStore(inner -> {})))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("already running on this thread");
    }

    @Test
    @DisplayName("the graph-anchoring overload is not re-entry")
    void anchoringOverloadIsOneEntry() {
        withSeededStore("anchored", dsl ->
            assertThat(dsl.fetchCount(STORE_GRAPH)).isOne());
    }

    @Test
    @DisplayName("the funnel boots one store per booting thread, not one per case")
    void bootsOncePerThread() {
        withSeededStore(dsl -> {});
        assertThat(ThreadConfinedStore.boots())
            .as("one boot per thread that has run a funnel case; more means a store was opened"
                + " per case somewhere in the funnel")
            .isEqualTo(ThreadConfinedStore.bootingThreads());
    }

    @Test
    @DisplayName("the module stays inside its store budget")
    void staysInsideTheBootBudget() {
        assertThat(FactStores.boots())
            .as("the funnel's boots plus the per-case boots of the classes whose subject is the"
                + " boot path; every funnel call checks this too, so the count is bounded wherever"
                + " in the run it goes over rather than only where this class happens to run")
            .isLessThanOrEqualTo(ThreadConfinedStore.bootBudget());
    }
}
