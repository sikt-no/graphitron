package no.sikt.graphitron.rewrite.capture;

import no.sikt.graphitron.model.boot.GraphitronModelStore;
import no.sikt.graphitron.rewrite.CapturedStore;
import no.sikt.graphitron.rewrite.NodeDeclaration;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static no.sikt.graphitron.model.Tables.GRAPHQL_TYPE;
import static no.sikt.graphitron.model.Tables.STORE_GRAPH;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The persisted store's bootstrap: what survives a run, what falls back, and how two writers
 * share one file.
 *
 * <p>Every negative case here has the same answer, and that is the point. The store is shared by
 * a workspace's modules and never deleted by code: a hand-damaged file, a stamp naming another
 * DDL, and a file that is not a database at all each cost this run its warmth (the open falls
 * back to a private in-memory store) and cost the file nothing, because one file is every
 * module's warmth and no local failure earns the right to destroy it. The compatibility stamp in
 * the store's own directory name is what makes that safe: an ordinary upgrade opens a different
 * file instead of meeting one it cannot read, so the fallback is reserved for damage.
 */
@UnitTier
class PersistentStoreTest {

    private static final String SDL = """
        type Query { films: [Film!]! }
        type Film { title: String }
        """;

    private static final String GRAPH_NAME = "PersistentStoreTest";

    @Test
    @DisplayName("a store written under a home is there for the next run")
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
    @DisplayName("an in-memory store never claims to be warm, and reports no location")
    void inMemoryIsAlwaysCold() {
        try (var store = GraphitronModelStore.open()) {
            assertThat(store.warm()).isFalse();
            assertThat(store.location()).isEmpty();
        }
    }

    @Test
    @DisplayName("a stamp naming a different DDL falls back, and the file survives")
    void aStaleStampFallsBackAndTheFileSurvives(@TempDir Path tmp) {
        Path directory = tmp.resolve("graphitron-model");
        captureInto(directory, tmp);

        Path location;
        try (var store = GraphitronModelStore.openAt(directory)) {
            location = store.location().orElseThrow();
            store.dsl().execute("UPDATE store_stamp SET ddl_hash = 'a schema this build never wrote'");
        }

        try (var fallback = GraphitronModelStore.openAt(directory)) {
            assertThat(fallback.warm()).as("a store stamped by another DDL is not this run's").isFalse();
            assertThat(fallback.location())
                .as("the run got the in-memory fallback, not the file").isEmpty();
            assertThat(fallback.dsl().fetchCount(GRAPHQL_TYPE)).isZero();
        }
        assertThat(Files.isRegularFile(location.resolve("store.mv.db")))
            .as("the never-discard rule's observable consequence: the damaged file is still on disk")
            .isTrue();
    }

    @Test
    @DisplayName("a file that is not a database falls back, and the file survives")
    void anUnreadableFileFallsBackAndSurvives(@TempDir Path tmp) throws IOException {
        Path directory = tmp.resolve("graphitron-model");
        Path location;
        try (var store = GraphitronModelStore.openAt(directory)) {
            location = store.location().orElseThrow();
        }
        byte[] damage = "not a database".getBytes(StandardCharsets.UTF_8);
        Files.write(location.resolve("store.mv.db"), damage);

        try (var fallback = GraphitronModelStore.openAt(directory)) {
            assertThat(fallback.warm()).isFalse();
            assertThat(fallback.location()).isEmpty();
            assertThat(fallback.dsl().fetchCount(GRAPHQL_TYPE)).isZero();
        }
        assertThat(Files.readAllBytes(location.resolve("store.mv.db")))
            .as("the file is left byte-identical for whoever can repair it").isEqualTo(damage);
    }

    /**
     * A second opener never destroys the first one's store. In one JVM that is H2 handing both
     * handles the same database, and the thing being pinned is that closing one does not take the
     * other's database with it: the store issues no SHUTDOWN for a file it is meant to leave
     * behind. The cross-process arm is {@link #aSecondProcessAttachesAndBothWritersLand}: mixed
     * mode is load-bearing for the shared store, so the second JVM's machinery is now the price
     * of pinning the property this class actually relies on, where it used to buy one arm of a
     * fallback.
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
            assertThat(held.location()).isPresent();
        }
    }

    /**
     * The mixed-mode property in the only shape that can pin it: two handles in one JVM already
     * share one database with no {@code AUTO_SERVER} anywhere, so an in-process case would be
     * green without the mechanism. What is new is cross-process: a second <em>process</em>
     * attaches and writes instead of being handed the in-memory fallback, and it survives the
     * server-holding process closing its connection mid-session, which is what keeps the
     * open-time fallback from being a way for cache trouble to cost correctness after all.
     */
    @Test
    @DisplayName("a second process attaches, survives the holder closing, and both writers land")
    void aSecondProcessAttachesAndBothWritersLand(@TempDir Path tmp) throws Exception {
        Path directory = tmp.resolve("graphitron-model");
        captureInto(directory, tmp);

        var held = GraphitronModelStore.openAt(directory);
        assertThat(held.location()).as("the test holds the file store").isPresent();
        held.dsl().execute("INSERT INTO graphql_type (graph_name, type_name, kind) "
            + "VALUES ('" + GRAPH_NAME + "', 'HeldWritten', 'OBJECT')");

        Process child = new ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp", System.getProperty("java.class.path"),
            AttachingWriter.class.getName(), directory.toString(), GRAPH_NAME)
            .redirectErrorStream(true)
            .start();
        try (var out = new BufferedReader(new InputStreamReader(child.getInputStream()))) {
            // The JVM may prepend housekeeping lines (JAVA_TOOL_OPTIONS echoes); the marker is
            // what the child prints once it holds a warm attached handle.
            var preamble = new StringBuilder();
            String line;
            while ((line = out.readLine()) != null && !line.equals("ATTACHED")) {
                preamble.append(line).append('\n');
            }
            assertThat(line).as("the child attached to the held store; it said:\n" + preamble)
                .isEqualTo("ATTACHED");
            // The server-holding process's connection closes while the child's session is live.
            held.close();
            child.getOutputStream().write('\n');
            child.getOutputStream().flush();
            assertThat(child.waitFor(60, TimeUnit.SECONDS)).as("child exited").isTrue();
            assertThat(child.exitValue()).as(out.lines().reduce("", (a, b) -> a + "\n" + b)).isZero();
        }

        try (var reopened = GraphitronModelStore.openAt(directory)) {
            assertThat(reopened.dsl().select(GRAPHQL_TYPE.TYPE_NAME).from(GRAPHQL_TYPE)
                .fetch(0, String.class))
                .as("both processes' rows are in the file")
                .contains("HeldWritten", "ChildWrittenBefore", "ChildWrittenAfter");
        }
    }

    /** The forked half of the mixed-mode case; exits non-zero on any shape it must not meet. */
    public static final class AttachingWriter {
        public static void main(String[] args) throws IOException {
            try (var store = GraphitronModelStore.openAt(Path.of(args[0]))) {
                if (store.location().isEmpty()) {
                    System.out.println("fell back to the in-memory store instead of attaching");
                    System.exit(2);
                }
                if (!store.warm()) {
                    System.out.println("attached onto an empty store");
                    System.exit(3);
                }
                store.dsl().execute("INSERT INTO graphql_type (graph_name, type_name, kind) "
                    + "VALUES ('" + args[1] + "', 'ChildWrittenBefore', 'OBJECT')");
                System.out.println("ATTACHED");
                System.out.flush();
                int ignored = System.in.read();
                store.dsl().execute("INSERT INTO graphql_type (graph_name, type_name, kind) "
                    + "VALUES ('" + args[1] + "', 'ChildWrittenAfter', 'OBJECT')");
            }
        }
    }

    @Test
    @DisplayName("a graph name recorded against a different directory is not taken over")
    void aClaimedGraphNameIsNotTakenOver(@TempDir Path tmp) throws IOException {
        Path directory = tmp.resolve("graphitron-model");
        Path original = Files.createDirectories(tmp.resolve("original"));
        FactCapture.run(directory, new FactCapture.GraphIdentity(GRAPH_NAME, original),
            FactCapture.SubjectConfig.none(), CapturedStore.registryOf(original, SDL),
            CapturedStore.attributionOf(original), null, List.of());
        List<String> before = typeNames(directory);

        Path impostor = Files.createDirectories(tmp.resolve("impostor"));
        FactCapture.run(directory, new FactCapture.GraphIdentity(GRAPH_NAME, impostor),
            FactCapture.SubjectConfig.none(),
            CapturedStore.registryOf(impostor, "type Query { other: Int }"),
            CapturedStore.attributionOf(impostor), null, List.of());

        assertThat(typeNames(directory))
            .as("the recorded partition, byte-identical after the colliding run")
            .isEqualTo(before);
        try (var store = GraphitronModelStore.openAt(directory)) {
            assertThat(store.dsl().select(STORE_GRAPH.BASE_DIR).from(STORE_GRAPH)
                .where(STORE_GRAPH.GRAPH_NAME.eq(GRAPH_NAME)).fetchOne(0, String.class))
                .as("the name still belongs to the directory that recorded it")
                .isEqualTo(original.toAbsolutePath().normalize().toString());
        }
    }

    @Test
    @DisplayName("no home means an in-memory capture, not a file")
    void noHomeMeansInMemory(@TempDir Path tmp) {
        FactCapture.run(null, graph(tmp), FactCapture.SubjectConfig.none(),
            CapturedStore.registryOf(tmp, SDL), CapturedStore.attributionOf(tmp), null, List.of());
        assertThat(Files.exists(tmp.resolve("graphitron-model")))
            .as("nothing was written for a caller with no home to give").isFalse();
    }

    private static FactCapture.GraphIdentity graph(Path baseDir) {
        return new FactCapture.GraphIdentity(GRAPH_NAME, baseDir);
    }

    private static void captureInto(Path directory, Path scratch) {
        FactCapture.run(directory, graph(scratch), FactCapture.SubjectConfig.none(),
            CapturedStore.registryOf(scratch, SDL), CapturedStore.attributionOf(scratch), null,
            List.of());
    }

    private static List<String> typeNames(Path directory) {
        try (var store = GraphitronModelStore.openAt(directory)) {
            return store.dsl().select(GRAPHQL_TYPE.TYPE_NAME).from(GRAPHQL_TYPE)
                .orderBy(GRAPHQL_TYPE.TYPE_NAME).fetch(0, String.class);
        }
    }

    /** The same capture cold, so the warm expectation is a measurement rather than a magic number. */
    private static int typeCount(Path directory, Path scratch) {
        try (var cold = GraphitronModelStore.open()) {
            FactCapture.capture(cold.dsl(), graph(scratch), FactCapture.SubjectConfig.none(),
                CapturedStore.registryOf(scratch, SDL), CapturedStore.attributionOf(scratch));
            return cold.dsl().fetchCount(GRAPHQL_TYPE);
        }
    }
}
