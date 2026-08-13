package no.sikt.graphitron.rewrite.capture;

import graphql.schema.idl.TypeDefinitionRegistry;
import no.sikt.graphitron.model.boot.GraphitronModelStore;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static no.sikt.graphitron.model.Tables.STORE_GRAPH;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The reader a store mints for a consumer that answers questions while somebody else writes: what
 * it can see, and when it can see it.
 *
 * <p>Two properties carry the whole design, and both are about a boundary rather than a query. A
 * round in flight is invisible, so no answer is ever assembled from half a capture. And one read is
 * one snapshot, so a handler running several queries cannot report a schema that never existed by
 * seeing one commit for its first query and the next for its second. Neither rests on an isolation
 * default: the reader sets its level when it is minted, and these are the cases that would fail if
 * it stopped.
 */
@UnitTier
class StoreReaderTest {

    private static final String SDL = """
        type Query { films: [Film!]! }
        type Film { title: String }
        """;

    @Test
    void aReaderSeesWhatTheWriterCommitted(@TempDir Path tmp) {
        var registry = CapturedStore.registryOf(tmp, SDL);
        try (var store = GraphitronModelStore.open(); var reader = store.reader()) {
            captureAs(store, "committed", tmp, registry);

            assertThat(reader.read(StoreReaderTest::graphNames)).containsExactly("committed");
        }
    }

    @Test
    void aRoundStillInFlightIsInvisible(@TempDir Path tmp) {
        var registry = CapturedStore.registryOf(tmp, SDL);
        try (var store = GraphitronModelStore.open(); var reader = store.reader()) {
            captureAs(store, "landed", tmp, registry);

            // The writer's transaction is open for the duration of this block, which is what a
            // capture round is. A reader sharing the writer's connection could not ask at all here.
            List<String> during = store.dsl().transactionResult(tx -> {
                writeGraphRow(tx.dsl(), "mid-flight", tmp);
                return reader.read(StoreReaderTest::graphNames);
            });

            assertThat(during)
                .as("a partition being written is not an answer, however complete it looks")
                .containsExactly("landed");
            assertThat(reader.read(StoreReaderTest::graphNames))
                .as("and once it commits, the next read has it")
                .containsExactly("landed", "mid-flight");
        }
    }

    @Test
    void oneReadIsOneSnapshot(@TempDir Path tmp) {
        var registry = CapturedStore.registryOf(tmp, SDL);
        try (var store = GraphitronModelStore.open(); var reader = store.reader()) {
            captureAs(store, "first", tmp, registry);

            // Both queries belong to one read, with a whole round committing between them: the
            // shape of a handler that asks about a type and then about its fields.
            List<List<String>> asked = reader.read(dsl -> {
                List<String> before = graphNames(dsl);
                captureAs(store, "second", tmp, registry);
                return List.of(before, graphNames(dsl));
            });

            assertThat(asked.getFirst()).containsExactly("first");
            assertThat(asked.getLast())
                .as("the second query answers from the snapshot the first one did")
                .containsExactly("first");
            assertThat(reader.read(StoreReaderTest::graphNames))
                .as("the commit is not lost, only deferred to the next read")
                .containsExactly("first", "second");
        }
    }

    @Test
    void aPersistedStoreMintsAReaderOntoItsOwnFile(@TempDir Path tmp) {
        var registry = CapturedStore.registryOf(tmp, SDL);
        Path home = tmp.resolve("store-home");
        try (var store = GraphitronModelStore.openAt(home); var reader = store.reader()) {
            captureAs(store, "persisted", tmp, registry);

            assertThat(store.location())
                .as("the file-backed shape, so the reader resolved a path rather than a memory name")
                .isPresent();
            assertThat(reader.read(StoreReaderTest::graphNames)).containsExactly("persisted");
        }
    }

    @Test
    void closingOneReaderLeavesTheStoreAndItsSiblingsReadable(@TempDir Path tmp) {
        var registry = CapturedStore.registryOf(tmp, SDL);
        try (var store = GraphitronModelStore.open()) {
            captureAs(store, "shared", tmp, registry);
            var first = store.reader();
            var second = store.reader();

            first.close();

            assertThat(second.read(StoreReaderTest::graphNames)).containsExactly("shared");
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
     * A bare anchor row, for the cases that need a write inside a transaction they control rather
     * than the one {@link FactCapture#capture} opens for itself.
     */
    private static void writeGraphRow(DSLContext dsl, String graphName, Path baseDir) {
        dsl.insertInto(STORE_GRAPH)
            .set(STORE_GRAPH.GRAPH_NAME, graphName)
            .set(STORE_GRAPH.BASE_DIR, baseDir.toString())
            .set(STORE_GRAPH.LAST_CAPTURED, java.time.LocalDateTime.now())
            .execute();
    }

    private static void captureAs(GraphitronModelStore store, String graphName, Path directory,
                                  TypeDefinitionRegistry registry) {
        FactCapture.capture(store.dsl(), new FactCapture.GraphIdentity(graphName, directory),
            FactCapture.SubjectConfig.none(), registry, CapturedStore.attributionOf(directory));
    }
}
