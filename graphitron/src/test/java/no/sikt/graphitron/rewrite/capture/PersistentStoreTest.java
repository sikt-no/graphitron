package no.sikt.graphitron.rewrite.capture;

import no.sikt.graphitron.model.boot.GraphitronModelStore;
import no.sikt.graphitron.model.boot.ReadBudget;
import no.sikt.graphitron.rewrite.CapturedStore;
import no.sikt.graphitron.rewrite.NodeDeclaration;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.jooq.exception.DataAccessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransactionRollbackException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static no.sikt.graphitron.model.Tables.GRAPHQL_TYPE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_TYPE_COORDINATE;
import static no.sikt.graphitron.model.Tables.STORE_GRAPH;
import static no.sikt.graphitron.model.Tables.STORE_SOURCE;
import static no.sikt.graphitron.model.test.StoreAnswers.answered;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * The persisted store's bootstrap: what survives a run, what falls back, and how two writers
 * share one file.
 *
 * <p>Every negative case here has the same answer, and that is the point. The store is shared by
 * a workspace's modules and never deleted by code: a hand-damaged file, a stamp naming another
 * DDL, a file that is not a database at all, and a file another process holds each cost this run
 * its warmth (the open falls back to a private in-memory store) and cost the file nothing, because
 * one file is every module's warmth and no local failure earns the right to destroy it. The
 * compatibility stamp in the store's own directory name is what makes that safe: an ordinary
 * upgrade opens a different file instead of meeting one it cannot read, so the fallback is
 * reserved for damage.
 *
 * <p>The same answer has to arrive <em>fast</em>, which is the second half of what this class
 * pins. A contended cache that answers in two minutes of silence is indistinguishable from a hung
 * build, so the cases below bound both layers where a run meets a busy store: the open, which must
 * demote rather than wait on another process, and the capture, which gives up the anchor row on a
 * short budget while keeping the generous one for every row after it.
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
     * The sharing that survives refusing to share a file across processes, and the sharing every
     * reader surface depends on. H2 gives one process one database per file and hands further
     * connections off it without consulting the file lock, so a second handle in this JVM opens
     * <em>onto the file</em> rather than into the fallback, reads what the holder committed, and
     * leaves the holder's database alive when it closes: the store issues no SHUTDOWN for a file it
     * is meant to leave behind. This is what a reactor build's modules and a dev session's readers
     * are, so pinning it is what keeps the cross-process refusal a warmth trade rather than a
     * reader losing its rows.
     */
    @Test
    @DisplayName("a second opener in this JVM gets the file, reads it, and leaves it intact")
    void aSecondOpenerLeavesTheStoreIntact(@TempDir Path tmp) {
        Path directory = tmp.resolve("graphitron-model");
        captureInto(directory, tmp);
        int types = typeCount(directory, tmp);

        try (var held = GraphitronModelStore.openAt(directory)) {
            try (var second = GraphitronModelStore.openAt(directory)) {
                assertThat(second.location())
                    .as("a second opener in the holding process gets the file, not the fallback")
                    .isPresent();
                assertThat(second.dsl().fetchCount(GRAPHQL_TYPE)).isEqualTo(types);
            }
            try (var reader = held.reader(new ReadBudget.Unbounded())) {
                int readerTypes = answered(reader.read(dsl -> dsl.fetchCount(GRAPHQL_TYPE)));
                assertThat(readerTypes)
                    .as("the reader surface the LSP and MCP answer from").isEqualTo(types);
            }
            assertThat(held.dsl().fetchCount(GRAPHQL_TYPE))
                .as("the holder's database outlived the other handle's close").isEqualTo(types);
            assertThat(held.location()).isPresent();
        }
    }

    /**
     * The open cannot block, in the only shape that can pin it. Two handles in one JVM share a
     * database and never reach the file lock, so a second <em>process</em> is what meets it: it is
     * refused by the operating system and demotes to memory, where it used to attach through an
     * embedded server whose liveness probe had no read timeout and could block forever on a
     * suspended or hard-killed holder. The timeout is part of the assertion rather than a
     * guardrail: a mechanism that can hang has to fail this test instead of wedging the build.
     */
    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    @DisplayName("a store another process holds demotes this run rather than blocking its open")
    void aHeldFileDemotesInsteadOfBlocking(@TempDir Path tmp) throws Exception {
        Path directory = tmp.resolve("graphitron-model");
        captureInto(directory, tmp);

        Process holder = new ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp", System.getProperty("java.class.path"),
            HoldingWriter.class.getName(), directory.toString(), GRAPH_NAME)
            .redirectErrorStream(true)
            .start();
        try (var out = new BufferedReader(new InputStreamReader(holder.getInputStream()))) {
            // The JVM may prepend housekeeping lines (JAVA_TOOL_OPTIONS echoes); the marker is
            // what the child prints once it holds the file.
            var preamble = new StringBuilder();
            String line;
            while ((line = out.readLine()) != null && !line.equals("HELD")) {
                preamble.append(line).append('\n');
            }
            assertThat(line).as("the child holds the store; it said:\n" + preamble).isEqualTo("HELD");

            long start = System.nanoTime();
            try (var mine = GraphitronModelStore.openAt(directory)) {
                long elapsed = millisSince(start);
                assertThat(mine.location())
                    .as("a file another process holds is not this run's; it captures in memory")
                    .isEmpty();
                assertThat(mine.warm()).isFalse();
                assertThat(elapsed)
                    .as("the open is bounded by the file lock, not by a probe that reads a socket "
                        + "with no timeout")
                    .isLessThan(GraphitronModelStore.FILE_LOCK_MILLIS);
            }

            // And the run on top of that open completes rather than stalling: this is the whole
            // reported symptom, a build in a checkout where a dev session is running.
            FactCapture.run(directory, graph(tmp), FactCapture.SubjectConfig.none(),
                CapturedStore.registryOf(tmp, SDL), CapturedStore.attributionOf(tmp), null,
                List.of());

            holder.getOutputStream().write('\n');
            holder.getOutputStream().flush();
            assertThat(holder.waitFor(60, TimeUnit.SECONDS)).as("the holder exited").isTrue();
            assertThat(holder.exitValue()).as(out.lines().reduce("", (a, b) -> a + "\n" + b)).isZero();
        }

        try (var reopened = GraphitronModelStore.openAt(directory)) {
            assertThat(reopened.warm())
                .as("the holder's file is intact and warm for the next run").isTrue();
            assertThat(reopened.dsl().select(GRAPHQL_TYPE_COORDINATE.TYPE_NAME)
                .from(GRAPHQL_TYPE_COORDINATE).fetch(0, String.class))
                .as("the holder's own write survived the run that could not have the file")
                .contains("HolderWritten");
        }
    }

    /** The forked holder; exits non-zero on any shape it must not meet. */
    public static final class HoldingWriter {
        public static void main(String[] args) throws IOException {
            try (var store = GraphitronModelStore.openAt(Path.of(args[0]))) {
                if (store.location().isEmpty()) {
                    System.out.println("fell back to the in-memory store instead of taking the file");
                    System.exit(2);
                }
                if (!store.warm()) {
                    System.out.println("opened onto an empty store");
                    System.exit(3);
                }
                // The coordinate anchor, not the attribute relation beside it: the probe's subject
                // is that the holder's write survives, and the anchor is the relation with no
                // parent to seed.
                store.dsl().execute("INSERT INTO graphql_type_coordinate (graph_name, type_name) "
                    + "VALUES ('" + args[1] + "', 'HolderWritten')");
                System.out.println("HELD");
                System.out.flush();
                int ignored = System.in.read();
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

    /**
     * The anchor row is the one row a capture will not wait out. A second writer holding it
     * uncommitted is another capture of the same graph mid-flight, and waiting for it buys the
     * right to delete that capture and write it again identically, so the capture gives up on a
     * short budget instead. What the elapsed bound pins is that the budget is a fraction of the
     * generous one, which is what stops a contended store reading as a hang.
     */
    @Test
    @DisplayName("a held anchor row gives up fast rather than waiting out the generous budget")
    void aHeldAnchorRowGivesUpFast(@TempDir Path tmp) throws Exception {
        Path directory = tmp.resolve("graphitron-model");
        captureInto(directory, tmp);

        try (var holder = GraphitronModelStore.openAt(directory);
             var writer = GraphitronModelStore.openAt(directory)) {
            holdUncommitted(holder, () -> holder.dsl().update(STORE_GRAPH)
                .set(STORE_GRAPH.LAST_CAPTURED, LocalDateTime.now())
                .where(STORE_GRAPH.GRAPH_NAME.eq(GRAPH_NAME)).execute());

            long start = System.nanoTime();
            var thrown = catchThrowableOfType(DataAccessException.class, () ->
                FactCapture.capture(writer.dsl(), true, graph(tmp), FactCapture.SubjectConfig.none(),
                    CapturedStore.registryOf(tmp, SDL), CapturedStore.attributionOf(tmp), null,
                    List.of()));
            long elapsed = millisSince(start);

            assertThat(thrown).as("the capture gave up on the anchor row").isNotNull();
            assertThat(FactCapture.timedOutOnALock(thrown))
                .as("it gave up on a lock budget, which is what the retry must not re-enter")
                .isTrue();
            assertThat(elapsed)
                .as("it waited its own budget out, and a fraction of the generous one")
                .isBetween(FactCapture.ANCHOR_LOCK_MILLIS / 2,
                    GraphitronModelStore.FILE_LOCK_MILLIS / 4);
        }
    }

    /**
     * The generous budget survives the anchor, which is what keeps the row-scoped narrowing from
     * quietly retiring the case the generous budget exists for. {@code store_source} rows are
     * store-global, so two different graphs' captures write them concurrently and the other writer
     * is committing rows this one also needs, within seconds: there a writer that waits its turn
     * beats one that falls back cold. So a capture whose anchor is free waits past the anchor budget
     * for a store-global row and lands, where lowering one number to the other would fail it.
     */
    @Test
    @DisplayName("a capture waits past the anchor budget for a store-global row")
    void theGenerousBudgetSurvivesTheAnchor(@TempDir Path tmp) throws Exception {
        Path directory = tmp.resolve("graphitron-model");
        captureInto(directory, tmp);
        // Comfortably past the anchor budget and comfortably short of the generous one, so the test
        // distinguishes the two without waiting out either.
        long holdMillis = 6_000;

        try (var holder = GraphitronModelStore.openAt(directory);
             var writer = GraphitronModelStore.openAt(directory)) {
            assertThat(holder.dsl().fetchCount(STORE_SOURCE))
                .as("the capture recorded the sources this holder is about to hold").isPositive();
            holder.connection().setAutoCommit(false);
            holder.dsl().update(STORE_SOURCE)
                .set(STORE_SOURCE.LAST_SEEN, LocalDateTime.now()).execute();
            var release = new Thread(() -> {
                try {
                    Thread.sleep(holdMillis);
                    holder.connection().rollback();
                    holder.connection().setAutoCommit(true);
                } catch (InterruptedException | SQLException e) {
                    throw new IllegalStateException(e);
                }
            });

            long start = System.nanoTime();
            release.start();
            FactCapture.capture(writer.dsl(), true, graph(tmp), FactCapture.SubjectConfig.none(),
                CapturedStore.registryOf(tmp, SDL), CapturedStore.attributionOf(tmp), null,
                List.of());
            long elapsed = millisSince(start);
            release.join();

            assertThat(elapsed)
                .as("the capture waited for the store-global row rather than giving up on the "
                    + "anchor budget")
                .isGreaterThan(holdMillis / 2);
            assertThat(writer.dsl().fetchCount(GRAPHQL_TYPE))
                .as("and landed once the other writer released it").isPositive();
        }
    }

    /**
     * The demotion end to end, which is the behaviour a user meets: a build whose anchor row is
     * held by a live session stops waiting, captures in memory, and returns. The file is what
     * proves it went cold rather than winning the row, being byte-for-byte the other writer's.
     */
    @Test
    @DisplayName("a run that meets a held anchor row completes cold and leaves the file alone")
    void aRunThatMeetsAHeldAnchorCompletesCold(@TempDir Path tmp) throws Exception {
        Path directory = tmp.resolve("graphitron-model");
        captureInto(directory, tmp);
        List<String> before = typeNames(directory);

        long start;
        try (var holder = GraphitronModelStore.openAt(directory)) {
            holdUncommitted(holder, () -> holder.dsl().update(STORE_GRAPH)
                .set(STORE_GRAPH.LAST_CAPTURED, LocalDateTime.now())
                .where(STORE_GRAPH.GRAPH_NAME.eq(GRAPH_NAME)).execute());
            start = System.nanoTime();
            FactCapture.run(directory, graph(tmp), FactCapture.SubjectConfig.none(),
                CapturedStore.registryOf(tmp, SDL), CapturedStore.attributionOf(tmp), null,
                List.of());
        }

        assertThat(millisSince(start))
            .as("the whole run stopped waiting well short of the generous budget, where it used to "
                + "spend two of them in silence")
            .isLessThan(GraphitronModelStore.FILE_LOCK_MILLIS / 4);
        assertThat(typeNames(directory))
            .as("the demoted run wrote nothing to the file").isEqualTo(before);
    }

    /**
     * The retry survives its original purpose. A lock timeout is the one cause that is not retried,
     * because the wait it reports is the whole of what waiting had to offer; a deadlock is the
     * transient casualty the retry was written for, and the driver spells that difference in the
     * JDBC exception rather than in a message.
     */
    @Test
    @DisplayName("a lock timeout is not retried; every other write failure still is")
    void onlyALockTimeoutSkipsTheRetry() {
        assertThat(FactCapture.timedOutOnALock(
            new DataAccessException("wrapped", new SQLTimeoutException("Timeout trying to lock table"))))
            .as("a lock budget that expired, however deeply wrapped").isTrue();
        assertThat(FactCapture.timedOutOnALock(new DataAccessException("wrapped",
            new SQLException("outer", new SQLTimeoutException("Timeout trying to lock table")))))
            .as("H2 wraps its own store's failure, and jOOQ wraps that").isTrue();
        assertThat(FactCapture.timedOutOnALock(
            new DataAccessException("wrapped", new SQLTransactionRollbackException("Deadlock"))))
            .as("a deadlock is the transient casualty the retry exists for").isFalse();
        assertThat(FactCapture.timedOutOnALock(
            new DataAccessException("wrapped", new SQLException("Unique index violation"))))
            .as("a capture bug is retried once, so it fails the same way twice and says so").isFalse();
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

    /**
     * Runs {@code write} on {@code store}'s connection and leaves it uncommitted, so the rows it
     * touched stay locked until the store closes. Two connections in one JVM lock exactly as two
     * processes do, which is what lets the contention cases stay deterministic and unforked.
     */
    private static void holdUncommitted(GraphitronModelStore store, Runnable write)
            throws SQLException {
        store.connection().setAutoCommit(false);
        write.run();
    }

    private static long millisSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
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
