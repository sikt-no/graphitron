package no.sikt.graphitron.model.sink;

import no.sikt.graphitron.model.test.CapturedStore;
import no.sikt.graphitron.model.test.CatalogInput;
import no.sikt.graphitron.model.test.TestRunContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static no.sikt.graphitron.model.Tables.STORE_GRAPH_WRITE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A finished capture records how long it spent writing each table, under the gatherer writing it,
 * and every finished capture keeps its own rows.
 */
class WriteTimesTest {

    @TempDir
    Path tmp;

    private static final String SDL = """
        type Query { films: [Film!]! }
        type Film @table(name: "film") { title: String }
        """;

    @Test
    @DisplayName("a stage's table is timed under the gatherer that writes it, the run's own rows are not")
    void aStagesWriteIsTimedUnderItsGatherer() {
        try (var store = CapturedStore.ownStoreOfCatalog(tmp, SDL, CatalogInput.of(TestRunContext.of()))) {
            var w = STORE_GRAPH_WRITE;
            var rows = store.dsl().selectFrom(w).where(w.GRAPH_NAME.eq(CapturedStore.GRAPH)).fetch();

            assertThat(rows)
                .filteredOn(r -> r.getTableName().equals("GRAPHITRON_FIELD_SOURCE_TABLE"))
                .singleElement()
                .satisfies(r -> {
                    assertThat(r.getGathererName()).isEqualTo("graphitron");
                    assertThat(r.getStatements()).isPositive();
                    assertThat(r.getRowsWritten()).isPositive();
                });
            assertThat(rows).extracting(r -> r.getTableName())
                .as("the run's own record is not part of what it times")
                .doesNotContain("STORE_GRAPH_PROGRESS", "STORE_GRAPH_WRITE");
            assertThat(rows).extracting(r -> r.getCapturedAt()).as("one capture's rows").containsOnly(
                rows.get(0).getCapturedAt());
        }
    }

    @Test
    @DisplayName("a second capture keeps the first one's rows, and the slowest are read from the latest")
    void everyCaptureKeepsItsRows() {
        var jooq = CatalogInput.of(TestRunContext.of());
        try (var store = CapturedStore.ownStoreOfCatalog(tmp, SDL, jooq)) {
            var w = STORE_GRAPH_WRITE;
            var first = store.dsl().fetchCount(w, w.GRAPH_NAME.eq(CapturedStore.GRAPH));

            store.recaptureCatalog(SDL, jooq);

            var captures = store.dsl().selectDistinct(w.CAPTURED_AT).from(w)
                .where(w.GRAPH_NAME.eq(CapturedStore.GRAPH)).orderBy(w.CAPTURED_AT)
                .fetch(w.CAPTURED_AT);
            assertThat(captures).hasSize(2);
            assertThat(store.dsl().fetchCount(w, w.CAPTURED_AT.eq(captures.get(0)))).isEqualTo(first);
            assertThat(WriteTimes.slowest(store.dsl(), CapturedStore.GRAPH, 1000))
                .as("only the latest capture's")
                .hasSize(store.dsl().fetchCount(w, w.CAPTURED_AT.eq(captures.get(1))));
        }
    }
}
