package no.sikt.graphitron.model.boot;

import no.sikt.graphitron.model.test.FactStores;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static no.sikt.graphitron.model.Tables.STORE_GRAPH;
import static no.sikt.graphitron.model.test.StoreAnswers.answered;
import static no.sikt.graphitron.model.test.SeededStore.seedGraph;
import static no.sikt.graphitron.model.test.SeededStore.seedGraphSource;
import static no.sikt.graphitron.model.test.SeededStore.seedSource;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The reader a store mints for a consumer that answers questions while somebody else writes: what
 * it can see, and when it can see it.
 *
 * <p>One property is about the boundary itself. One read is one snapshot, so a handler running
 * several queries cannot report a schema that never existed by seeing one commit for its first
 * query and the next for its second. It does not rest on an isolation default: the reader sets its
 * level when it is minted, and this is the case that fails if it stops.
 *
 * <p>The other two are about which connection a reader holds. It is minted onto the store's own
 * database, which for a persisted store means the file that store actually opened rather than one
 * a reader recomputed a path to and found empty. And it owns nothing but itself, so closing one
 * leaves the store and any sibling reader reading.
 *
 * <p>Rows arrive here by seeding, in transactions this class opens itself, because what a reader
 * must not see mid-round is a set of inserts rather than a document somebody parsed. That the
 * generator's own {@code FactCapture} writes a whole graph in one such round is its property and
 * was never this class's claim.
 */
class StoreReaderTest {

    /**
     * Every case here is about which rows a reader sees, never about how long a read took, so each
     * one names its budget structurally as the absence of one. What a budget does is
     * {@link StoreBudgetTest}'s subject.
     */
    private static final ReadBudget UNBOUNDED = new ReadBudget.Unbounded();

    @Test
    void oneReadIsOneSnapshot() {
        try (var store = FactStores.inMemory(); var reader = store.reader(UNBOUNDED)) {
            seedRound(store.dsl(), "first");

            // Both queries belong to one read, with a whole round committing between them: the
            // shape of a handler that asks about a type and then about its fields.
            List<List<String>> asked = answered(reader.read(dsl -> {
                List<String> before = graphNames(dsl);
                seedRound(store.dsl(), "second");
                return List.of(before, graphNames(dsl));
            }));

            assertThat(asked.getFirst()).containsExactly("first");
            assertThat(asked.getLast())
                .as("the second query answers from the snapshot the first one did")
                .containsExactly("first");
            assertThat(answered(reader.read(StoreReaderTest::graphNames)))
                .as("the commit is not lost, only deferred to the next read")
                .containsExactly("first", "second");
        }
    }

    @Test
    void aPersistedStoreMintsAReaderOntoItsOwnFile(@TempDir Path tmp) {
        try (var store = FactStores.fileBacked(tmp.resolve("store-home"));
             var reader = store.reader(UNBOUNDED)) {
            seedRound(store.dsl(), "persisted");

            assertThat(store.location())
                .as("the file-backed shape, so the reader resolved a path rather than a memory name")
                .isPresent();
            assertThat(answered(reader.read(StoreReaderTest::graphNames)))
                .containsExactly("persisted");
        }
    }

    @Test
    void closingOneReaderLeavesTheStoreAndItsSiblingsReadable() {
        try (var store = FactStores.inMemory()) {
            seedRound(store.dsl(), "shared");
            var first = store.reader(UNBOUNDED);
            var second = store.reader(UNBOUNDED);

            first.close();

            assertThat(answered(second.read(StoreReaderTest::graphNames))).containsExactly("shared");
            assertThat(graphNames(store.dsl())).containsExactly("shared");
            second.close();
            assertThat(graphNames(store.dsl()))
                .as("a reader closing is not the database closing")
                .containsExactly("shared");
        }
    }

    private static List<String> graphNames(DSLContext dsl) {
        return dsl.select(STORE_GRAPH.GRAPH_NAME).from(STORE_GRAPH)
            .orderBy(STORE_GRAPH.GRAPH_NAME)
            .fetch(STORE_GRAPH.GRAPH_NAME);
    }

    /**
     * Several rows and one commit, which is all a reader can tell about a capture round from its
     * own side: rows that are not there, and then all of them at once.
     */
    private static void seedRound(DSLContext dsl, String graphName) {
        dsl.transaction(tx -> {
            seedGraph(tx.dsl(), graphName);
            seedSource(tx.dsl(), graphName + ".graphqls", "SCHEMA_FILE");
            seedGraphSource(tx.dsl(), graphName, graphName + ".graphqls");
        });
    }
}
