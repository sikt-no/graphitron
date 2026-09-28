package no.sikt.graphitron.model.test;

import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicReference;

import static no.sikt.graphitron.model.Tables.STORE_GRAPH;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link CorpusStore}'s read-only claim, one enforcer per case: the reader's rollback, and the
 * handout check for each of the two writes a rollback does not stop. Each case writes, so each
 * runs on a private store filled with one small document rather than on the shared corpus, which
 * the write would break for every class after it.
 */
class CorpusStoreTest {

    @TempDir
    Path tmp;

    private CorpusStore store() {
        return CorpusStore.privately(() -> CapturedStore.ownStore(tmp, "type Query { a: Int }\n"));
    }

    @Test
    void aRowWrittenInsideAReadIsRolledBack() {
        var store = store();
        try {
            store.run(dsl -> writeARow(dsl, "rolled-back"));
            int graphs = store.read(dsl -> dsl.fetchCount(STORE_GRAPH));
            assertThat(graphs)
                .as("the handout check passed and the row never landed")
                .isOne();
        } finally {
            store.close();
        }
    }

    @Test
    void aTableCreatedThroughAReaderFailsTheNextHandout() {
        var store = store();
        try {
            store.run(dsl -> dsl.execute("CREATE TABLE leaked_by_reader (id INT)"));
            assertThatThrownBy(store::reader)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("LEAKED_BY_READER");
        } finally {
            store.close();
        }
    }

    @Test
    void aRowWrittenThroughAContextKeptPastTheReadFailsTheNextHandout() {
        var store = store();
        try {
            var leaked = new AtomicReference<DSLContext>();
            try (var reader = store.reader()) {
                reader.read(dsl -> {
                    leaked.set(dsl);
                    return null;
                });
                writeARow(leaked.get(), "leaked");
            }
            assertThatThrownBy(store::reader)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("STORE_GRAPH");
        } finally {
            store.close();
        }
    }

    /**
     * One row in {@code store_graph}, the relation a write most plainly lands in. What it says is
     * beside the point: the subject is whether a write reaches the store at all.
     */
    private static void writeARow(DSLContext dsl, String graphName) {
        dsl.insertInto(STORE_GRAPH)
            .set(STORE_GRAPH.GRAPH_NAME, graphName)
            .set(STORE_GRAPH.BASE_DIR, "/written")
            .set(STORE_GRAPH.LAST_CAPTURED, LocalDateTime.now())
            .execute();
    }
}
