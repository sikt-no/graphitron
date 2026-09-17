package no.sikt.graphitron.rewrite.capture;

import no.sikt.graphitron.model.boot.GraphitronModelStore;
import no.sikt.graphitron.model.boot.ReadBudget;
import no.sikt.graphitron.model.boot.StoreReaper;
import no.sikt.graphitron.model.boot.StoreUnavailableException;
import no.sikt.graphitron.model.test.CapturedStore;
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
import static no.sikt.graphitron.model.Tables.GRAPHQL_TYPE_ELEMENT;
import static no.sikt.graphitron.model.Tables.STORE_GRAPH;
import static no.sikt.graphitron.model.Tables.STORE_SOURCE;
import static no.sikt.graphitron.model.test.StoreAnswers.answered;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import no.sikt.graphitron.model.capture.FactCapture;
import no.sikt.graphitron.model.run.GraphIdentity;
import no.sikt.graphitron.model.run.GraphitronStore;
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
    @DisplayName("a stamp naming a different DDL is refused, and the file survives")
    void aStaleStampIsRefusedAndTheFileSurvives(@TempDir Path tmp) {
        Path directory = tmp.resolve("graphitron-model");
        captureInto(directory, tmp);

        Path location;
        try (var store = GraphitronModelStore.openAt(directory)) {
            location = store.location().orElseThrow();
            store.dsl().execute("UPDATE store_stamp SET ddl_hash = 'a schema this build never wrote'");
        }

        assertThatThrownBy(() -> GraphitronModelStore.openAt(directory))
            .as("a store stamped by another DDL is not this run's, and under the stamped path only "
                + "a hand can have put it there, so the person is told to delete it")
            .isInstanceOf(StoreUnavailableException.class)
            .hasMessageContaining("moved or damaged by hand")
            .hasMessageContaining("nothing is lost");
        assertThat(Files.isRegularFile(location.resolve("store.mv.db")))
            .as("the observable consequence of a failed open deleting nothing: the damaged file is "
                + "still on disk, and the sweep spares the live stamp by name whatever state it is in")
            .isTrue();
    }

    @Test
    @DisplayName("a file that is not a database is refused, and the file survives")
    void anUnreadableFileIsRefusedAndSurvives(@TempDir Path tmp) throws IOException {
        Path directory = tmp.resolve("graphitron-model");
        Path location;
        try (var store = GraphitronModelStore.openAt(directory)) {
            location = store.location().orElseThrow();
        }
        byte[] damage = "not a database".getBytes(StandardCharsets.UTF_8);
        Files.write(location.resolve("store.mv.db"), damage);

        assertThatThrownBy(() -> GraphitronModelStore.openAt(directory))
            .isInstanceOf(StoreUnavailableException.class);
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
    @DisplayName("a store another process holds fails this run rather than blocking its open")
    void aHeldFileFailsInsteadOfBlocking(@TempDir Path tmp) throws Exception {
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
            assertThatThrownBy(() -> GraphitronModelStore.openAt(directory))
                .as("a file another process holds is not this run's, and it says so")
                .isInstanceOf(StoreUnavailableException.class)
                .hasMessageContaining("another graphitron process holding it");
            assertThat(millisSince(start))
                .as("the open is bounded by H2's own refusal of a held file, not by a probe that "
                    + "reads a socket with no timeout")
                .isLessThan(30_000);

            // And the run on top of that open fails rather than stalling. Failing fast is the
            // half that was ever in question: the reported symptom was a build that hung in a
            // checkout where a dev session was running, and a bounded refusal answers it.
            assertThatThrownBy(() -> FactCapture.run(directory, graph(tmp), SubjectConfig.none(),
                CapturedStore.registryOf(tmp, SDL), CapturedStore.attributionOf(tmp), null,
                List.of()))
                .as("and the run on top of it refuses, rather than capturing somewhere the caller "
                    + "did not ask for")
                .isInstanceOf(StoreUnavailableException.class);

            holder.getOutputStream().write('\n');
            holder.getOutputStream().flush();
            assertThat(holder.waitFor(60, TimeUnit.SECONDS)).as("the holder exited").isTrue();
            assertThat(holder.exitValue()).as(out.lines().reduce("", (a, b) -> a + "\n" + b)).isZero();
        }

        try (var reopened = GraphitronModelStore.openAt(directory)) {
            assertThat(reopened.warm())
                .as("the holder's file is intact and warm for the next run").isTrue();
            assertThat(reopened.dsl().select(GRAPHQL_TYPE_ELEMENT.TYPE_NAME)
                .from(GRAPHQL_TYPE_ELEMENT).fetch(0, String.class))
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
                // Both rows carry the instant of the reading that wrote them, which every anchor a
                // mark and sweep governs requires; a row with none would be a row no reading claims.
                store.dsl().execute("INSERT INTO graphql_element "
                    + "(graph_name, coordinate, element_kind, touched_at) "
                    + "VALUES ('" + args[1] + "', 'HolderWritten', 'NAMED_TYPE', CURRENT_TIMESTAMP)");
                store.dsl().execute("INSERT INTO graphql_type_element "
                    + "(graph_name, type_name, coordinate, touched_at) "
                    + "VALUES ('" + args[1] + "', 'HolderWritten', 'HolderWritten', "
                    + "CURRENT_TIMESTAMP)");
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
        assertThatThrownBy(() -> FactCapture.run(directory, new GraphIdentity(GRAPH_NAME, impostor),
            SubjectConfig.none(),
            CapturedStore.registryOf(impostor, "type Query { other: Int }"),
            CapturedStore.attributionOf(impostor), null, List.of()))
            .as("the colliding run is refused, and told which setting separates the two")
            .isInstanceOf(StoreUnavailableException.class)
            .hasMessageContaining(GRAPH_NAME)
            .hasMessageContaining("<graphName>");

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
     * A run gets the store it asked for or it is told why not, and the second is an exception
     * rather than a value hung off the store, because there is no store to hang it off any more.
     * The one arm that is not trouble is a caller with no home to name: that caller asked for a
     * private in-memory store, and asking for one is not losing one.
     */
    @Test
    @DisplayName("a run gets the store it asked for, or is told what to do about it")
    void aRunGetsTheStoreItAskedFor(@TempDir Path tmp) throws IOException {
        Path directory = tmp.resolve("graphitron-model");
        Path original = Files.createDirectories(tmp.resolve("original"));
        try (RunStore store = forRun(directory, original)) {
            assertThat(store).as("a fresh home nobody holds is the store under it")
                .isInstanceOf(RunStore.Owned.class);
            assertThat(store.store().location())
                .as("and it is the file, not a private stand-in").isNotEmpty();
        }

        try (RunStore store = forRun(null, original)) {
            assertThat(store.store().location())
                .as("no home to name is a private in-memory store, which is what was asked for")
                .isEmpty();
            assertThat(store.handle().dsl().fetchCount(GRAPHQL_TYPE))
                .as("and it captured the same facts the file would have").isPositive();
        }

        Path impostor = Files.createDirectories(tmp.resolve("impostor"));
        assertThatThrownBy(() -> forRun(directory, impostor))
            .as("a name another checkout recorded names both directories and the setting between")
            .isInstanceOf(StoreUnavailableException.class)
            .hasMessageContaining(original.toAbsolutePath().normalize().toString())
            .hasMessageContaining(impostor.toAbsolutePath().normalize().toString());
    }

    /**
     * A capture meets a held row and stops, on the anchor as anywhere else. The connection carries
     * no lock budget at all now, so what the elapsed bound pins is that nothing was waited out:
     * this row used to have a short budget of its own and everything after it a generous one, and
     * the argument between them was about which rows are worth waiting for. None are.
     */
    @Test
    @DisplayName("a held anchor row stops the capture at once rather than waiting")
    void aHeldAnchorRowStopsTheCaptureAtOnce(@TempDir Path tmp) throws Exception {
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
            assertThat(GraphitronStore.contendedLock(thrown))
                .as("it gave up on a lock, which is what turns this into a message rather than "
                    + "the driver's own words")
                .isTrue();
            assertThat(elapsed)
                .as("and it waited for none of it. The bound sits below H2's own 2000 ms default "
                    + "on purpose: that default is what a connection gets when the lock budget is "
                    + "absent or spelled as zero, so this is the case that fails if anybody tidies "
                    + "the budget to the obvious wrong number")
                .isLessThan(1_500);
        }
    }

    /**
     * The rule holds past the anchor too, which is the half a row-scoped budget could have hidden.
     * {@code store_source} rows are store-global, and this case used to pin a capture waiting six
     * seconds for one and landing, on the argument that a writer who waits his turn beats one who
     * falls back cold. There is no falling back cold, and two modules are two files, so the writer
     * this waited for cannot arrive on the path that has a budget to spend. Same fixture, opposite
     * rule: a held row anywhere ends the capture, and it does not sleep on the way out.
     */
    @Test
    @DisplayName("a held store-global row stops the capture too, and waits out none of the hold")
    void aHeldStoreGlobalRowStopsTheCapture(@TempDir Path tmp) throws Exception {
        Path directory = tmp.resolve("graphitron-model");
        captureInto(directory, tmp);
        // Long enough that a capture which waited for the row would be caught doing it.
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
            var thrown = catchThrowableOfType(DataAccessException.class, () ->
                FactCapture.capture(writer.dsl(), true, graph(tmp), SubjectConfig.none(),
                    CapturedStore.registryOf(tmp, SDL), CapturedStore.attributionOf(tmp), null,
                    List.of()));
            long elapsed = millisSince(start);
            release.join();

            assertThat(thrown).as("the held store-global row ended the capture").isNotNull();
            assertThat(GraphitronStore.contendedLock(thrown))
                .as("on a lock, past the anchor, where a budget used to make this one wait")
                .isTrue();
            assertThat(elapsed)
                .as("and none of the hold was waited out")
                .isLessThan(holdMillis / 2);
        }
    }

    /**
     * The refusal end to end, which is the behaviour a user meets: a build whose anchor row is held
     * by a live session stops waiting and says which graph is contended, rather than spending the
     * generous budget in silence or capturing where nobody asked. The file is what proves it never
     * won the row, being byte-for-byte the other writer's.
     */
    @Test
    @DisplayName("a run that meets a held anchor row fails fast and leaves the file alone")
    void aRunThatMeetsAHeldAnchorFailsFast(@TempDir Path tmp) throws Exception {
        Path directory = tmp.resolve("graphitron-model");
        captureInto(directory, tmp);
        List<String> before = typeNames(directory);

        long start;
        try (var holder = GraphitronModelStore.openAt(directory)) {
            holdUncommitted(holder, () -> holder.dsl().update(STORE_GRAPH)
                .set(STORE_GRAPH.LAST_CAPTURED, LocalDateTime.now())
                .where(STORE_GRAPH.GRAPH_NAME.eq(GRAPH_NAME)).execute());
            start = System.nanoTime();
            assertThatThrownBy(() -> FactCapture.run(directory, graph(tmp), SubjectConfig.none(),
                CapturedStore.registryOf(tmp, SDL), CapturedStore.attributionOf(tmp), null,
                List.of()))
                .as("a contended lock is named as another process writing the store, not handed "
                    + "over as the driver's own words")
                .isInstanceOf(StoreUnavailableException.class)
                .hasMessageContaining(GRAPH_NAME)
                .hasMessageContaining("let it finish and run again");
        }

        assertThat(millisSince(start))
            .as("the whole run stopped at once, where it used to spend two lock budgets in "
                + "silence and then capture somewhere nobody asked")
            .isLessThan(15_000);
        assertThat(typeNames(directory))
            .as("the refused run wrote nothing to the file").isEqualTo(before);
    }

    /**
     * The predicate outlived the retry it was written for, and now decides a message instead of a
     * second attempt: a contended anchor row is the one write failure a person can act on, so it
     * gets said in their words. Everything else is a capture bug and keeps the driver's, there
     * being nothing to tell them to go and do. The driver spells the difference in the JDBC
     * exception rather than in a message, which is what makes this checkable at all.
     */
    @Test
    @DisplayName("a lock timeout is told apart from every other write failure")
    void aLockTimeoutIsToldApart() {
        assertThat(GraphitronStore.contendedLock(
            new DataAccessException("wrapped", new SQLTimeoutException("Timeout trying to lock table"))))
            .as("a lock budget that expired, however deeply wrapped").isTrue();
        assertThat(GraphitronStore.contendedLock(new DataAccessException("wrapped",
            new SQLException("outer", new SQLTimeoutException("Timeout trying to lock table")))))
            .as("H2 wraps its own store's failure, and jOOQ wraps that").isTrue();
        assertThat(GraphitronStore.contendedLock(
            new DataAccessException("wrapped", new SQLTransactionRollbackException("Deadlock"))))
            .as("a deadlock is not lock contention, and is not described as it").isFalse();
        assertThat(GraphitronStore.contendedLock(
            new DataAccessException("wrapped", new SQLException("Unique index violation"))))
            .as("a capture bug keeps the driver's own words, having nothing to advise").isFalse();
    }

    /**
     * The other half of what the retry needs to be right, and the half a capture's own atomicity used
     * to supply for free. An attempt has to know whether it is walking into rows of its own, and the
     * store's warm flag is fixed when the store opens: it answered that question only while a capture
     * was all-or-nothing, a failed attempt rolling back so that the next one met the store the first
     * one found. The analysing refresh cadence commits this graph's facts, its anchor row and its
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

    /**
     * The last handle to let a file go compacts it, and no earlier one does. This is the whole of
     * what keeps the file a cache: a plain close leaves H2 its default 200 ms of compaction, which
     * reclaims nothing once a store has been cleared and rewritten enough times, so the file grows
     * without bound while the row count does not. The sibling assertion is the one that matters for
     * correctness: a handle that is not the last must report nothing, because compacting there
     * would shut the database under whoever still holds it.
     */
    @Test
    @DisplayName("the last handle compacts the file and an earlier one does not")
    void theLastHandleCompacts(@TempDir Path tmp) {
        Path directory = tmp.resolve("graphitron-model");
        captureInto(directory, tmp);

        GraphitronModelStore held = GraphitronModelStore.openAt(directory);
        GraphitronModelStore second = GraphitronModelStore.openAt(directory);
        second.close();
        assertThat(second.compaction())
            .as("a handle that is not the last leaves the database alone")
            .isEmpty();

        held.close();
        assertThat(held.compaction())
            .as("the last handle to let the file go compacts it")
            .isPresent();
    }

    /**
     * Closing twice is the same as closing once. The handle count cannot survive a double
     * decrement: the second release would give back a slot this store never held, and the next
     * close would then compact while another holder still had the database open.
     */
    @Test
    @DisplayName("closing a store twice releases one handle, not two")
    void closingTwiceReleasesOneHandle(@TempDir Path tmp) {
        Path directory = tmp.resolve("graphitron-model");
        captureInto(directory, tmp);

        GraphitronModelStore first = GraphitronModelStore.openAt(directory);
        try (var second = GraphitronModelStore.openAt(directory)) {
            first.close();
            first.close();
            assertThat(second.dsl().fetchCount(GRAPHQL_TYPE))
                .as("the surviving handle still has its database")
                .isNotNegative();
        }
    }

    /**
     * The compaction reclaims, which is the half the mechanism cases cannot see. They pin that the
     * last handle compacts and an earlier one does not, and both stay true if
     * {@code SHUTDOWN COMPACT} is swapped for a statement that leaves the file alone; this one
     * fails against that swap, because it reads the file on both sides of the shutdown and requires
     * it to have actually shrunk.
     *
     * <p>Recapturing is what makes the garbage to reclaim. Each capture clears the families it
     * rewrites and writes them again, and H2 appends the new pages rather than overwriting the old,
     * so a store captured into repeatedly carries every superseded copy until something drops them.
     *
     * <p>The assertion is on the reclamation rather than on growth across closes, which is the
     * shape this file grows in and not a shape a test can hold. An uncompacted store oscillates
     * within a band rather than climbing: measured over eight captures it ran 2.77, 3.82, 3.62,
     * 2.17, 4.24 MB and back, so the file after four captures is as likely to sit below the file
     * after one as above it, and a bound loose enough not to flake is loose enough to pass with the
     * compaction removed. The unbounded growth this item exists to stop emerges over hundreds of
     * runs, which is not a unit test. What is checkable at every close is that the close reclaims,
     * and a close that reclaims every time is what keeps the long curve flat.
     *
     * <p>Halving is the bound because the reclaimable share follows the capture count rather than
     * the graph: five captures leave roughly five copies of a live set of one, so the ratio sits
     * near a fifth (measured 643,072 against 2,433,024) whatever the fixture holds. Enlarging the
     * SDL above moves both sides together and does not eat the margin.
     */
    @Test
    @DisplayName("the last handle's compaction reclaims what recapture left behind")
    void compactionReclaimsWhatRecaptureLeaves(@TempDir Path tmp) {
        Path directory = tmp.resolve("graphitron-model");
        captureInto(directory, tmp);

        // A handle held across the captures, which is a dev session beside a build and is also what
        // keeps the garbage: no capture's own close is the last one, so none of them compacts.
        GraphitronModelStore held = GraphitronModelStore.openAt(directory);
        try {
            for (int recapture = 1; recapture <= 4; recapture++) {
                captureInto(directory, tmp);
            }
        } finally {
            held.close();
        }

        var compaction = held.compaction().orElseThrow(
            () -> new AssertionError("the only handle on the file reported no compaction"));
        // Both sizes first, because the record reports an unreadable file as -1 and the ratio below
        // is satisfied by -1 against -1: a measurement that failed outright would otherwise pass
        // the assertion that exists to catch a compaction that did nothing.
        assertThat(compaction.bytesBefore())
            .as("the file was measured before the shutdown").isPositive();
        assertThat(compaction.bytesAfter())
            .as("the file was measured after the shutdown").isPositive();
        assertThat(compaction.bytesAfter())
            .as("the file after the shutdown against before it (before=%d, after=%d): five captures"
                + " of one small graph leave far more file than facts, and the compaction is what"
                + " gives it back", compaction.bytesBefore(), compaction.bytesAfter())
            .isLessThan(compaction.bytesBefore() / 2);
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
