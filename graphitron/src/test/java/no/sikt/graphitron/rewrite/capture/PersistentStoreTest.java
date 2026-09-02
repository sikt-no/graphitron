package no.sikt.graphitron.rewrite.capture;

import no.sikt.graphitron.model.boot.GraphitronModelStore;
import no.sikt.graphitron.model.boot.ReadBudget;
import no.sikt.graphitron.model.boot.StoreReaper;
import no.sikt.graphitron.rewrite.CapturedStore;
import no.sikt.graphitron.model.grammar.NodeDeclaration;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.h2.jdbcx.JdbcDataSource;
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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import no.sikt.graphitron.model.capture.FactCapture;
import no.sikt.graphitron.model.run.GraphIdentity;
import no.sikt.graphitron.model.run.RunStore;
import no.sikt.graphitron.model.run.SubjectConfig;

/**
 * The persisted store's bootstrap: what survives a run, what falls back, and how two writers
 * share one file.
 *
 * <p>Every negative case here has the same answer, and that is the point. The store is shared by
 * a workspace's modules and no failure to open one deletes it: a hand-damaged file, a stamp naming
 * another DDL, a file that is not a database at all, and a file another process holds each cost this
 * run its warmth (the open falls back to a private in-memory store) and cost the file nothing, because
 * one file is every module's warmth and no local failure earns the right to destroy it. What the
 * store does release is a different population and answers to a different rule, which the sweep case
 * at the end of this class pins across a process boundary. The
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
            .as("the observable consequence of a failed open deleting nothing: the damaged file is "
                + "still on disk, and the sweep spares the live stamp by name whatever state it is in")
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
            FactCapture.run(directory, graph(tmp), SubjectConfig.none(),
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
                // The coordinate anchor and the supertype row above it, which is the shallowest
                // pair this store admits: the probe's subject is that the holder's write survives,
                // so it writes the least that a coordinate needs and nothing else.
                store.dsl().execute("INSERT INTO graphql_coordinate (graph_name, coordinate, kind) "
                    + "VALUES ('" + args[1] + "', 'HolderWritten', 'TYPE')");
                store.dsl().execute("INSERT INTO graphql_type_coordinate "
                    + "(graph_name, type_name, coordinate) "
                    + "VALUES ('" + args[1] + "', 'HolderWritten', 'HolderWritten')");
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
        FactCapture.run(directory, new GraphIdentity(GRAPH_NAME, original),
            SubjectConfig.none(), CapturedStore.registryOf(original, SDL),
            CapturedStore.attributionOf(original), null, List.of());
        List<String> before = typeNames(directory);

        Path impostor = Files.createDirectories(tmp.resolve("impostor"));
        FactCapture.run(directory, new GraphIdentity(GRAPH_NAME, impostor),
            SubjectConfig.none(),
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
     * Every way a run can lose the shared store is a value it can be asked for rather than a log
     * line it has to match against its own run, which is what lets a case state the reason instead
     * of reading the build's output. Three arms have a fixture; the fourth, a write the shared file
     * refuses twice, is a deterministic capture bug by construction and has no fixture that is not
     * one.
     */
    @Test
    @DisplayName("a run says which store it got, and why when it is not the shared one")
    void aRunSaysWhichStoreItGot(@TempDir Path tmp) throws IOException {
        Path directory = tmp.resolve("graphitron-model");
        Path original = Files.createDirectories(tmp.resolve("original"));
        try (RunStore store = forRun(directory, original)) {
            assertThat(store).as("a fresh home nobody holds is the shared store")
                .isInstanceOf(RunStore.Shared.class);
            assertThat(store.demotion()).as("and it has nothing to explain").isEmpty();
        }

        try (RunStore store = forRun(null, original)) {
            assertThat(store.demotion())
                .as("no home to give is a demotion, and the one the caller asked for")
                .containsInstanceOf(RunStore.Demotion.NoHomeGiven.class);
            assertThat(store.handle().dsl().fetchCount(GRAPHQL_TYPE))
                .as("a demoted run captured the same facts a shared one would").isPositive();
        }

        Path impostor = Files.createDirectories(tmp.resolve("impostor"));
        try (RunStore store = forRun(directory, impostor)) {
            assertThat(store.demotion().orElseThrow())
                .as("the one demotion a consumer can fix names the directory holding the name")
                .isEqualTo(new RunStore.Demotion.GraphOwnedElsewhere(graph(impostor),
                    original.toAbsolutePath().normalize().toString()));
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
                FactCapture.capture(writer.dsl(), true, graph(tmp), SubjectConfig.none(),
                    CapturedStore.registryOf(tmp, SDL), CapturedStore.attributionOf(tmp), null,
                    List.of()));
            long elapsed = millisSince(start);

            assertThat(thrown).as("the capture gave up on the anchor row").isNotNull();
            assertThat(RunStore.timedOutOnALock(thrown))
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
            FactCapture.capture(writer.dsl(), true, graph(tmp), SubjectConfig.none(),
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
            FactCapture.run(directory, graph(tmp), SubjectConfig.none(),
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
        assertThat(RunStore.timedOutOnALock(
            new DataAccessException("wrapped", new SQLTimeoutException("Timeout trying to lock table"))))
            .as("a lock budget that expired, however deeply wrapped").isTrue();
        assertThat(RunStore.timedOutOnALock(new DataAccessException("wrapped",
            new SQLException("outer", new SQLTimeoutException("Timeout trying to lock table")))))
            .as("H2 wraps its own store's failure, and jOOQ wraps that").isTrue();
        assertThat(RunStore.timedOutOnALock(
            new DataAccessException("wrapped", new SQLTransactionRollbackException("Deadlock"))))
            .as("a deadlock is the transient casualty the retry exists for").isFalse();
        assertThat(RunStore.timedOutOnALock(
            new DataAccessException("wrapped", new SQLException("Unique index violation"))))
            .as("a capture bug is retried once, so it fails the same way twice and says so").isFalse();
    }

    /**
     * The other half of what the retry needs to be right, and the half a capture's own atomicity used
     * to supply for free. An attempt has to know whether it is walking into rows of its own, and the
     * store's warm flag is fixed when the store opens: it answered that question only while a capture
     * was all-or-nothing, a failed attempt rolling back so that the next one met the store the first
     * one found. The first-graph refresh cadence commits this graph's facts, its anchor row and its
     * hand-written derivations before it refreshes, so an attempt after a failed refresh meets a
     * partition its own predecessor wrote while the flag still reports the store as empty. Handed the
     * flag, it skips reconciliation and collides with itself on the first key it re-inserts, and the
     * collision is a plain write failure rather than a lock timeout, so the case above spends the
     * retry on it and the run is told it has a deterministic capture bug it does not have.
     *
     * <p>Asserted on the predicate for the same reason the case above is: no assertion over the
     * store's final rows can see it. The store self-heals on the next run, which opens warm against
     * null stamps and reloads the partition, so what a census would show is a store that is fine and
     * a retry that was spent.
     */
    @Test
    @DisplayName("what an attempt reconciles is read from the store, not from the open")
    void whatAnAttemptReconcilesIsReadFromTheStore(@TempDir Path tmp) {
        try (var store = GraphitronModelStore.openAt(tmp.resolve("graphitron-model"))) {
            assertThat(RunStore.reconciles(store, graph(tmp)))
                .as("a store holding no graph has nothing for a first attempt to reconcile")
                .isFalse();

            FactCapture.capture(store.dsl(), false, graph(tmp), SubjectConfig.none(),
                CapturedStore.registryOf(tmp, SDL), CapturedStore.attributionOf(tmp), null,
                List.of());

            assertThat(store.warm())
                .as("the open's answer, and it is stale: this handle has committed a partition since")
                .isFalse();
            assertThat(RunStore.reconciles(store, graph(tmp)))
                .as("what a retry after a failed refresh walks into, which is its own first attempt's"
                    + " committed partition")
                .isTrue();

            assertThatCode(() -> FactCapture.capture(store.dsl(),
                RunStore.reconciles(store, graph(tmp)), graph(tmp),
                SubjectConfig.none(), CapturedStore.registryOf(tmp, SDL),
                CapturedStore.attributionOf(tmp), null, List.of()))
                .as("the retry itself, taking what the predicate answered. Handed the open's answer"
                    + " instead it fails on the first key it re-inserts, which is a plain write"
                    + " failure and spends the retry the case above reserves for a casualty")
                .doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("no home means an in-memory capture, not a file")
    void noHomeMeansInMemory(@TempDir Path tmp) {
        FactCapture.run(null, graph(tmp), SubjectConfig.none(),
            CapturedStore.registryOf(tmp, SDL), CapturedStore.attributionOf(tmp), null, List.of());
        assertThat(Files.exists(tmp.resolve("graphitron-model")))
            .as("nothing was written for a caller with no home to give").isFalse();
    }

    private static GraphIdentity graph(Path baseDir) {
        return new GraphIdentity(GRAPH_NAME, baseDir);
    }

    /**
     * The one constraint the reaper rests on, pinned across a process boundary rather than only
     * within one JVM: a stamped directory recency would release survives while <em>another
     * process</em> holds its database, and goes once that process exits.
     *
     * <p>This is the {@code graphitron:dev} case as a user meets it. A session opened days ago holds
     * its stamp and has not written it since, so its directory is the oldest in the home while being
     * the one directory in the home that is genuinely in use. A same-JVM case cannot substitute:
     * H2 refusing this process's own lock is a different mechanism ({@code
     * OverlappingFileLockException}) from the operating system reporting another process's, and only
     * the second one is what a dev session actually presents.
     *
     * <p>Both sweeps go through {@code StoreReaper} directly rather than through {@code openAt},
     * because the once-per-home-per-JVM guard is exactly what would stop the second one. The
     * retention is one, so recency keeps nothing and every candidate reaches the probe: what survives
     * survived on the probe's answer alone.
     */
    @Test
    @Timeout(90)
    @DisplayName("a stamped directory another process holds survives the sweep, and goes once it exits")
    void aStampedDirectoryAnotherProcessHoldsSurvivesTheSweep(@TempDir Path tmp) throws Exception {
        Path home = Files.createDirectories(tmp.resolve("home"));
        Path held = Files.createDirectories(home.resolve("held-stamp"));
        Path unheld = Files.createDirectories(home.resolve("unheld-stamp"));
        Files.write(unheld.resolve("store.mv.db"), new byte[0]);

        Process holder = new ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp", System.getProperty("java.class.path"),
            HoldingDatabase.class.getName(), held.resolve("store").toString())
            .redirectErrorStream(true)
            .start();
        try (var out = new BufferedReader(new InputStreamReader(holder.getInputStream()))) {
            var preamble = new StringBuilder();
            String line;
            while ((line = out.readLine()) != null && !line.equals("HELD")) {
                preamble.append(line).append('\n');
            }
            assertThat(line).as("the child holds the database; it said:\n" + preamble).isEqualTo("HELD");

            var whileHeld = StoreReaper.sweep(home, "live-stamp", 1);

            assertThat(held.resolve("store.mv.db"))
                .as("a directory another process holds is never touched, whatever recency says")
                .exists();
            assertThat(unheld)
                .as("the unheld sibling went, so the sweep did run over this home")
                .doesNotExist();
            assertThat(whileHeld.directories()).isEqualTo(1);

            holder.getOutputStream().write('\n');
            holder.getOutputStream().flush();
            assertThat(holder.waitFor(60, TimeUnit.SECONDS)).as("the holder exited").isTrue();
            assertThat(holder.exitValue()).as(out.lines().reduce("", (a, b) -> a + "\n" + b)).isZero();
        }

        var afterExit = StoreReaper.sweep(home, "live-stamp", 1);

        assertThat(held).as("the operating system's lock died with the holder").doesNotExist();
        assertThat(afterExit.directories()).isEqualTo(1);
    }

    /**
     * The forked holder for the sweep case: an H2 file database at the path it is handed, opened the
     * way the store opens one (no {@code AUTO_SERVER}, so H2 takes the MVStore's own
     * operating-system lock rather than writing a lock file), held until stdin closes.
     *
     * <p>A sibling of {@link HoldingWriter} rather than a reuse of it, because that one opens through
     * {@code openAt} and therefore holds the <em>live</em> stamp, which the sweep spares by name. The
     * subject here is a stamp the sweep would otherwise release.
     */
    public static final class HoldingDatabase {
        public static void main(String[] args) throws IOException, SQLException {
            var source = new JdbcDataSource();
            source.setURL("jdbc:h2:file:" + args[0]);
            try (var connection = source.getConnection();
                 var statement = connection.createStatement()) {
                statement.execute("CREATE TABLE IF NOT EXISTS held (x INT)");
                System.out.println("HELD");
                System.out.flush();
                int ignored = System.in.read();
            }
        }
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

    /**
     * The store a run got, captured into and still open, for the cases whose subject is which
     * store that was.
     */
    private static RunStore forRun(Path directory, Path scratch) {
        var graph = graph(scratch);
        var registry = CapturedStore.registryOf(scratch, SDL);
        var attribution = CapturedStore.attributionOf(scratch);
        return RunStore.forRun(directory, graph, (dsl, warm) ->
            FactCapture.capture(dsl, warm, graph, SubjectConfig.none(), registry,
                attribution, null, List.of()));
    }

    private static void captureInto(Path directory, Path scratch) {
        FactCapture.run(directory, graph(scratch), SubjectConfig.none(),
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
            FactCapture.capture(cold.dsl(), graph(scratch), SubjectConfig.none(),
                CapturedStore.registryOf(scratch, SDL), CapturedStore.attributionOf(scratch));
            return cold.dsl().fetchCount(GRAPHQL_TYPE);
        }
    }
}
