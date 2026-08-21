package no.sikt.graphitron.model.test;

import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static no.sikt.graphitron.model.Tables.STORE_GRAPH;
import static no.sikt.graphitron.model.test.SeededStore.seedGraph;
import static no.sikt.graphitron.model.test.SeededStore.withSeededStore;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The funnel's own store discipline: that two bodies on one thread share a store, that the second
 * one cannot see the first one's rows, and that the module boots a store per thread rather than per
 * case.
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
