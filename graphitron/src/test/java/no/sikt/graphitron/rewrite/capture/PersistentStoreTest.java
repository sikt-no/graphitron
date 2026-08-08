package no.sikt.graphitron.rewrite.capture;

import no.sikt.graphitron.model.boot.GraphitronModelStore;
import no.sikt.graphitron.rewrite.NodeDeclaration;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static no.sikt.graphitron.model.Tables.GRAPHQL_TYPE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The persisted store's bootstrap: what survives a run, what does not, and what a second process
 * can read while a build holds the file.
 *
 * <p>Every negative case here has the same answer, and that is the point. A store is a build
 * artefact and never state of record, so "cannot be read" needs no taxonomy: an older schema, a
 * file a killed build left mid-write, and a file that is not a database at all are all discarded
 * and rebuilt, and the only thing a caller has to distinguish is warm from cold.
 */
@UnitTier
class PersistentStoreTest {

    private static final String SDL = """
        type Query { films: [Film!]! }
        type Film { title: String }
        """;

    @Test
    @DisplayName("a store written under a directory is there for the next run")
    void rowsSurviveTheRun(@TempDir Path tmp) {
        Path directory = tmp.resolve("graphitron-model");
        captureInto(directory, tmp);

        try (var reopened = GraphitronModelStore.openAt(directory)) {
            assertThat(reopened.warm()).as("a store with a matching stamp opens onto its rows").isTrue();
            assertThat(reopened.dsl().fetchCount(GRAPHQL_TYPE))
                .as("the previous run's type census").isEqualTo(typeCount(directory, tmp));
        }
    }

    @Test
    @DisplayName("an in-memory store never claims to be warm")
    void inMemoryIsAlwaysCold() {
        try (var store = GraphitronModelStore.open()) {
            assertThat(store.warm()).isFalse();
        }
    }

    @Test
    @DisplayName("a stamp naming a different DDL discards the file and rebuilds")
    void aStaleStampRebuilds(@TempDir Path tmp) {
        Path directory = tmp.resolve("graphitron-model");
        captureInto(directory, tmp);

        try (var store = GraphitronModelStore.openAt(directory)) {
            store.dsl().execute("UPDATE store_stamp SET ddl_hash = 'a schema this build never wrote'");
        }

        try (var rebuilt = GraphitronModelStore.openAt(directory)) {
            assertThat(rebuilt.warm()).as("a store built from another DDL is not warm, it is gone").isFalse();
            assertThat(rebuilt.dsl().fetchCount(GRAPHQL_TYPE)).isZero();
        }
    }

    @Test
    @DisplayName("a file that is not a database at all is discarded and rebuilt")
    void anUnreadableFileRebuilds(@TempDir Path tmp) throws IOException {
        Path directory = Files.createDirectories(tmp.resolve("graphitron-model"));
        Files.write(directory.resolve("store.mv.db"), "not a database".getBytes(StandardCharsets.UTF_8));

        try (var rebuilt = GraphitronModelStore.openAt(directory)) {
            assertThat(rebuilt.warm()).isFalse();
            assertThat(rebuilt.dsl().fetchCount(GRAPHQL_TYPE)).isZero();
        }
    }

    @Test
    @DisplayName("a reader opens a snapshot while the build still holds the store")
    void aSnapshotOpensBesideTheWriter(@TempDir Path tmp) {
        Path directory = tmp.resolve("graphitron-model");
        captureInto(directory, tmp);
        int types = typeCount(directory, tmp);

        // The writer's own handle, held open across the read, which is the case the copy exists for:
        // H2 gives a database one writer, and a reader that waited for the build would be useless.
        try (var writer = GraphitronModelStore.openAt(directory)) {
            var snapshot = GraphitronModelStore.openReadOnly(directory);
            assertThat(snapshot).as("a readable store yields a snapshot").isPresent();
            try (var reader = snapshot.get()) {
                assertThat(reader.warm()).isTrue();
                assertThat(reader.dsl().fetchCount(GRAPHQL_TYPE)).isEqualTo(types);
            }
            assertThat(writer.dsl().fetchCount(GRAPHQL_TYPE))
                .as("the snapshot took nothing away from the writer").isEqualTo(types);
        }
    }

    /**
     * A second opener never destroys the first one's store. In one JVM that is H2 handing both
     * handles the same database, and the thing being pinned is that closing one does not take the
     * other's database with it: the store issues no SHUTDOWN for a file it is meant to leave behind.
     * Across processes the same guarantee is reached the other way, by falling back to an in-memory
     * store rather than discarding a file H2 refused because someone else holds it, and that arm is
     * argued in {@code openAt} rather than staged here, a second JVM being a lot of machinery for
     * one branch.
     */
    @Test
    @DisplayName("a second opener leaves the first one's store intact")
    void aSecondOpenerLeavesTheStoreIntact(@TempDir Path tmp) {
        Path directory = tmp.resolve("graphitron-model");
        captureInto(directory, tmp);
        int types = typeCount(directory, tmp);

        try (var held = GraphitronModelStore.openAt(directory)) {
            try (var second = GraphitronModelStore.openAt(directory)) {
                assertThat(second.dsl().fetchCount(GRAPHQL_TYPE)).isEqualTo(types);
            }
            assertThat(held.dsl().fetchCount(GRAPHQL_TYPE))
                .as("the holder's database outlived the other handle's close").isEqualTo(types);
        }
        assertThat(Files.isRegularFile(directory.resolve("store.mv.db")))
            .as("and so did its file").isTrue();
    }

    @Test
    @DisplayName("no store, no snapshot")
    void noSnapshotWithoutAStore(@TempDir Path tmp) {
        assertThat(GraphitronModelStore.openReadOnly(tmp.resolve("never-written"))).isEmpty();
    }

    private static void captureInto(Path directory, Path scratch) {
        FactCapture.run(directory, CapturedStore.registryOf(scratch, SDL), null, List.of(),
            new NodeDeclaration(null));
    }

    /** The same capture cold, so the warm expectation is a measurement rather than a magic number. */
    private static int typeCount(Path directory, Path scratch) {
        try (var cold = GraphitronModelStore.open()) {
            FactCapture.capture(cold.dsl(), CapturedStore.registryOf(scratch, SDL));
            return cold.dsl().fetchCount(GRAPHQL_TYPE);
        }
    }
}
