package no.sikt.graphitron.model.test;

import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
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
            store.run(dsl -> SeededStore.seedGraph(dsl, "rolled-back"));
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
                SeededStore.seedGraph(leaked.get(), "leaked");
            }
            assertThatThrownBy(store::reader)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("STORE_GRAPH");
        } finally {
            store.close();
        }
    }
}
