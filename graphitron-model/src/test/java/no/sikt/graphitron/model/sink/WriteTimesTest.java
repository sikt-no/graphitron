package no.sikt.graphitron.model.sink;

import no.sikt.graphitron.model.config.ClasspathEntry;
import no.sikt.graphitron.model.test.CapturedStore;
import no.sikt.graphitron.model.test.CatalogInput;
import no.sikt.graphitron.model.test.TestRunContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static no.sikt.graphitron.model.Tables.STORE_GRAPH_WRITE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every finished capture keeps its own timing rows. What holds of any one capture's timings, which
 * gatherer a write is timed under, how a read is recorded and that the slowest statements are the
 * latest capture's, {@code CapturedGraphInvariantsTest} holds of every fact document's graph.
 *
 * <p>On a store of the test's own: a capture is timed by the wall clock rather than the scenario
 * clock, so how many captures a graph's rows came from is no row a document can claim. The stepped
 * scenario is read here in its three readings, one after another, as its claims see them.
 */
class WriteTimesTest {

    @TempDir
    Path tmp;

    private static final String GRAPH = "write-times";

    @Test
    @DisplayName("each capture keeps its own rows")
    void everyCaptureKeepsItsRows() {
        try (var store = CapturedStore.ownStoreOfDocument(tmp, "scenarios/minted-element-sweep", GRAPH,
                CatalogInput.of(TestRunContext.of()), List.<ClasspathEntry>of())) {
            var w = STORE_GRAPH_WRITE;
            var captures = store.dsl().selectDistinct(w.CAPTURED_AT).from(w)
                .where(w.GRAPH_NAME.eq(GRAPH)).orderBy(w.CAPTURED_AT)
                .fetch(w.CAPTURED_AT);
            assertThat(captures).as("one instant per reading the scenario takes").hasSize(3);
            captures.forEach(at -> assertThat(store.dsl().fetchCount(w, w.GRAPH_NAME.eq(GRAPH),
                    w.CAPTURED_AT.eq(at)))
                .as("the capture at %s keeps its rows", at)
                .isPositive());
        }
    }
}
