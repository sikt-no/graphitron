package no.sikt.graphitron.model.run;

import no.sikt.graphitron.model.boot.GraphitronModelStore;
import no.sikt.graphitron.model.read.StoreHandle;
import org.jooq.DSLContext;
import org.jooq.exception.DataAccessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.sql.SQLTimeoutException;
import java.util.Objects;
import java.util.Optional;

import static no.sikt.graphitron.model.Tables.STORE_GRAPH;

/**
 * The store a capture run got, open, and which store it is: the workspace's shared file, a private
 * in-memory one nothing outside this run will ever see, or one its caller opened and will close.
 * One value in place of the pair a caller used to work from, a nullable directory going in and a
 * log line coming out that it had to match against its own run.
 *
 * <p>The answer is final because it is given after the capture, not before it. Three of the four
 * ways a run loses the shared store are known when the store opens (no directory was named, the
 * file would not open, another checkout holds the graph name), but the fourth is not: a write the
 * shared file refuses twice demotes the run too, and that is only knowable once the write has been
 * attempted. So {@link #forRun} takes the capture as a {@link CaptureBody} and reports one outcome
 * for both halves, rather than answering early and leaving a caller holding a store that later
 * turns out not to be the one its facts went into.
 *
 * <p><b>A demotion costs warmth and nothing else.</b> Every arm captures the same rows and answers
 * the same reads; what a private store loses is the next run's head start. That is the rule the
 * whole fallback exists to keep, and it is why a deterministic capture bug demotes rather than
 * failing the build: a cache is never allowed to cost more than a cache is worth.
 *
 * <p>The value owns the store's lifetime, so a caller closes it when the run's reads are done.
 * {@link #handle()} is what those reads go through; the graph it carries is the partition they are
 * confined to.
 */
public sealed interface RunStore extends AutoCloseable {

    /** The open store, whose lifetime this value owns. */
    GraphitronModelStore store();

    /** The graph this run captured under, and the partition reads through it are scoped to. */
    GraphIdentity graph();

    /** Why this run is not on the shared store; empty when it is. */
    Optional<Demotion> demotion();

    /**
     * Captures into the store this value already holds, and says which store <em>that</em> turned
     * out to be. The same answer {@link #forRun} gives, for the second and later captures of a
     * caller that keeps one store across a session's passes rather than opening one per pass.
     *
     * <p>Returns a value rather than mutating, because a demotion is a different store: the arm
     * that comes back may hold a store the arm that went in did not, and the one that went in is
     * closed by then. A caller therefore keeps what this returns and closes only that.
     */
    RunStore recapture(CaptureBody body);

    /** The query surface the run's own readers ask through. */
    default StoreHandle handle() {
        return new StoreHandle(store().dsl(), graph().name());
    }

    @Override
    default void close() {
        store().close();
    }

    /** The run captured into the workspace's shared store, which is what every run wants. */
    record Shared(GraphitronModelStore store, GraphIdentity graph) implements RunStore {
        public Shared {
            Objects.requireNonNull(store, "store");
            Objects.requireNonNull(graph, "graph");
        }

        @Override
        public Optional<Demotion> demotion() {
            return Optional.empty();
        }

        /**
         * Retried and demoted exactly as the first capture was: a store that has served one
         * capture has earned no standing that would let the next one fail loudly.
         */
        @Override
        public RunStore recapture(CaptureBody body) {
            Optional<Demotion> refused = captureWithRetry(store, graph, body);
            if (refused.isEmpty()) {
                return this;
            }
            store.close();
            return inMemory(graph, body, refused.get());
        }
    }

    /** The run captured into a private in-memory store, and {@code reason} is why. */
    record Demoted(GraphitronModelStore store, GraphIdentity graph, Demotion reason)
        implements RunStore {
        public Demoted {
            Objects.requireNonNull(store, "store");
            Objects.requireNonNull(graph, "graph");
            Objects.requireNonNull(reason, "reason");
        }

        @Override
        public Optional<Demotion> demotion() {
            return Optional.of(reason);
        }

        /**
         * Straight through, with no retry and nothing to fall back to: this store is already the
         * fallback, and nobody else can be contending a database only this run can see. A failure
         * here is a capture bug with no cache left to pay for it, so it propagates.
         *
         * <p>The reason is not reported a second time. It was said when this store was arrived at,
         * and a session that captures on every keystroke would otherwise repeat one warning until
         * it stopped being read.
         */
        @Override
        public RunStore recapture(CaptureBody body) {
            body.capture(store.dsl(), reconciles(store, graph));
            return this;
        }
    }

    /**
     * The run captured into a store its caller opened and will close: a session that runs many
     * passes and hands each of them the one store it holds for its own readers. Structurally
     * separate from {@link Shared} because the only thing that differs is who closes, and that is
     * exactly the thing a caller must not get wrong.
     *
     * <p>{@code demotion} is the lender's own, not this run's: a store handed over may already be
     * the private fallback its opener arrived at, and a run on it should be able to say so. A
     * refusal met while capturing does not land here, it lands in {@link Demoted}, because losing
     * the lent store means capturing somewhere else.
     */
    record Borrowed(GraphitronModelStore store, GraphIdentity graph, Optional<Demotion> demotion)
        implements RunStore {
        public Borrowed {
            Objects.requireNonNull(store, "store");
            Objects.requireNonNull(graph, "graph");
            Objects.requireNonNull(demotion, "demotion");
        }

        /**
         * Retried as {@link Shared#recapture} is, and demoted to a private store the same way, with
         * the one difference that the lent store is left open: it is the caller's, and the caller's
         * readers are still on it.
         */
        @Override
        public RunStore recapture(CaptureBody body) {
            Optional<Demotion> refused = captureWithRetry(store, graph, body);
            return refused.isEmpty() ? this : inMemory(graph, body, refused.get());
        }

        /** Nothing: the lender closes what the lender opened. */
        @Override
        public void close() {
        }
    }

    /**
     * What a run writes into whichever store it got. The capture's content is the caller's, the
     * store's lifetime is this file's, and the {@code warm} flag is the one thing that crosses:
     * a body may be called twice, on the retry, and has to reconcile on a store its own first
     * attempt already wrote to.
     */
    @FunctionalInterface
    interface CaptureBody {
        /**
         * @param dsl  the store to fill
         * @param warm whether that store already holds rows this run owns and has to reconcile
         */
        void capture(DSLContext dsl, boolean warm);
    }

    /**
     * Why a run is capturing into a private store instead of the workspace's shared file. A value
     * rather than a log line, so a caller can say what happened and a test can pin which way it
     * went without reading the build's output. Reported once, by {@link #forRun}.
     */
    sealed interface Demotion {

        /** What a build is told when this demotion happens. */
        String message();

        /**
         * No directory was named, so there was no shared store to want. The one arm that is not
         * trouble: a caller with no home to give asked for exactly this.
         */
        record NoHomeGiven() implements Demotion {
            @Override
            public String message() {
                return "graphitron: no fact store directory was configured for this run, so its "
                    + "facts were captured in memory.";
            }
        }

        /**
         * The shared store could not be used: either it would not open at all, or the anchor row's
         * short budget expired with another writer still holding it.
         *
         * <p>One arm for both, and one message, because from where the user stands they are one
         * event with one consequence. The distinction matters to whoever fixes it, and a stack of
         * the stalled process is how they recover it, not a second sentence here.
         */
        record Unavailable() implements Demotion {

            /**
             * It names the likely holder because "could not use the store" on its own sends a user
             * looking for a stuck build rather than at the editor session they left running, says
             * what the run did instead because a warning with no consequence attached reads as
             * damage, and says the output is unaffected because a user who does not know the store
             * is a cache will read a warning about a database as their schema not having generated.
             */
            private static final String MESSAGE =
                "graphitron: could not use the shared fact store for this workspace, so this run "
                    + "captured its facts in memory instead of waiting for it. The usual cause is "
                    + "another graphitron process holding it, a `mvn graphitron:dev` session in the "
                    + "same checkout being the common one. This costs warm-start speed and nothing "
                    + "else: the generated output is identical.";

            @Override
            public String message() {
                return MESSAGE;
            }
        }

        /**
         * The graph name is recorded in the shared store against a different base directory, so
         * this run leaves that partition alone. The one demotion a consumer can fix, which is why
         * it names both directories and the setting that separates them.
         */
        record GraphOwnedElsewhere(GraphIdentity graph, String recordedBaseDir)
            implements Demotion {
            public GraphOwnedElsewhere {
                Objects.requireNonNull(graph, "graph");
                Objects.requireNonNull(recordedBaseDir, "recordedBaseDir");
            }

            @Override
            public String message() {
                return ("graphitron: graph '%s' is already recorded in the shared fact store for "
                    + "%s, but this run's base directory is %s. Leaving that partition alone and "
                    + "capturing in memory; set <graphName> so the two modules stop claiming one "
                    + "name.").formatted(graph.name(), recordedBaseDir, graph.baseDir());
            }
        }

        /**
         * Both attempts at the shared store failed the same way, which is a deterministic capture
         * bug rather than a concurrency casualty. The run still finishes, on a private store, and
         * this graph's warm start stays unavailable until the bug is fixed.
         */
        record CaptureFailedTwice(String graphName, DataAccessException failure)
            implements Demotion {
            public CaptureFailedTwice {
                Objects.requireNonNull(graphName, "graphName");
                Objects.requireNonNull(failure, "failure");
            }

            @Override
            public String message() {
                return ("graphitron: the shared fact store write for graph '%s' failed twice in a "
                    + "row; this looks like a deterministic capture bug rather than a concurrency "
                    + "casualty, and warm start will stay unavailable for this graph until it is "
                    + "fixed. Recapturing in memory for this run.").formatted(graphName);
            }
        }
    }

    /**
     * Opens the store this run captures into, fills it with {@code body}, and says which store
     * that turned out to be. Never fails for the store's sake: every way the shared file can be
     * refused ends in a private store holding the same rows.
     *
     * <p>With a directory the store is the shared file under it, so the run starts from the
     * previous runs' rows and rewrites only what it owns and cannot prove unchanged; without one it
     * is a private in-memory database, which is what every caller with no home to give should get.
     * The two differ in cost, never in content: a warm store is refreshed to exactly the rows a
     * cold load would have produced.
     *
     * <p>A write that fails against the shared file is retried once against that same file before
     * being demoted, which is what tells a concurrency casualty (a concurrent writer of the same
     * rows, a lock that timed out; cleared by the time the retry runs, since capture's own
     * delete-then-rewrite is safely rerunnable) apart from a deterministic capture bug (the same
     * failure both times, timing-independent). Only the second is loud, {@link
     * Demotion.CaptureFailedTwice} saying why.
     *
     * @param storeDirectory the workspace's store home, or {@code null} for a caller with none
     * @param graph          the coordinate this run writes under
     * @param body           the capture itself, called exactly once per attempt
     */
    static RunStore forRun(Path storeDirectory, GraphIdentity graph, CaptureBody body) {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(body, "body");
        if (storeDirectory == null) {
            return inMemory(graph, body, new Demotion.NoHomeGiven());
        }
        GraphitronModelStore shared = GraphitronModelStore.openAt(storeDirectory);
        // Whatever the open released from the cache home, said once. A run that quietly deletes
        // gigabytes out of a person's cache home should say so, and this is where an ordinary build
        // hears it: the store's once-per-JVM sweep guard makes the reporter whichever opener ran
        // first, which on a build is this one.
        shared.reaped().report(storeDirectory).ifPresent(log()::info);
        if (shared.location().isEmpty()) {
            // openAt already fell back to an in-memory store; use it as-is rather than opening a
            // second one. This is the one demotion no other layer reports: the store declines to
            // say why an open failed, and a silent one is what makes a build beside a dev session
            // look like a build that did nothing.
            return captureCold(shared, graph, body, new Demotion.Unavailable());
        }
        Optional<Demotion> refused = attemptShared(shared, graph, body);
        if (refused.isEmpty()) {
            return new Shared(shared, graph);
        }
        shared.close();
        return inMemory(graph, body, refused.get());
    }

    /**
     * {@link #forRun} for a caller that already holds an open store and wants this run to capture
     * into it: a dev session, whose readers are on that store and whose passes should write where
     * those readers look rather than each opening a database of their own.
     *
     * <p>The refusals are the same and reach the same end. The ownership check is asked of a warm
     * lent store exactly as it is of a warm one opened here, so a session whose graph name is
     * recorded against another checkout still leaves that partition alone; a write refused twice
     * still demotes. What differs is only what happens to the lent store when a refusal lands:
     * nothing, because it is not this run's to close.
     *
     * <p>A lent store that is itself already a fallback is reported here, once for the port's
     * lifetime rather than once per pass, because {@link GraphitronModelStore#openAt} declines to
     * say why an open failed and a silent demotion is what makes a dev session look like a session
     * that indexed nothing.
     *
     * @param lent  the caller's open store, which this run captures into and never closes
     * @param graph the coordinate this run writes under
     * @param body  the capture itself, called exactly once per attempt
     */
    static RunStore forRunOn(GraphitronModelStore lent, GraphIdentity graph, CaptureBody body) {
        Objects.requireNonNull(lent, "lent");
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(body, "body");
        if (lent.location().isEmpty()) {
            Demotion reason = new Demotion.Unavailable();
            report(reason);
            body.capture(lent.dsl(), reconciles(lent, graph));
            return new Borrowed(lent, graph, Optional.of(reason));
        }
        Optional<Demotion> refused = attemptShared(lent, graph, body);
        if (refused.isEmpty()) {
            return new Borrowed(lent, graph, Optional.empty());
        }
        return inMemory(graph, body, refused.get());
    }

    /**
     * The shared file's own attempt: the ownership check, then the capture with its one retry.
     * Empty when the run landed on the shared store; a reason when it has to be demoted.
     *
     * <p>Ownership is asked only of a warm store, a cold one having no row to disagree with.
     */
    private static Optional<Demotion> attemptShared(GraphitronModelStore shared, GraphIdentity graph,
                                                    CaptureBody body) {
        if (shared.warm()) {
            Optional<Demotion> refusal = ownership(shared.dsl(), graph);
            if (refusal.isPresent()) {
                return refusal;
            }
        }
        return captureWithRetry(shared, graph, body);
    }

    /**
     * Whether this run may write under its graph name: it may when the store has no row for it or
     * the recorded base directory is this run's own. The check lives here, where the store is open
     * and the row readable, rather than in the Maven goal, which never reads the store.
     */
    private static Optional<Demotion> ownership(DSLContext dsl, GraphIdentity graph) {
        String recorded = dsl.select(STORE_GRAPH.BASE_DIR).from(STORE_GRAPH)
            .where(STORE_GRAPH.GRAPH_NAME.eq(graph.name()))
            .fetchOne(0, String.class);
        if (recorded == null || recorded.equals(graph.baseDir().toString())) {
            return Optional.empty();
        }
        return Optional.of(new Demotion.GraphOwnedElsewhere(graph, recorded));
    }

    /**
     * Attempts the warm capture, retrying once against the same store before giving up, so a
     * transient concurrency casualty (cleared by the time the retry runs) is told apart from a
     * deterministic capture bug (fails the same way both times). Empty once either attempt lands;
     * a reason tells the caller to fall back to an in-memory capture instead.
     *
     * <p>A lock timeout is the one failure that is not retried, because the retry would re-enter a
     * wait that just expired: the budget the capture gave up on is the whole of what waiting had to
     * offer, and doubling a silent wait is precisely what made a contended store read as a hang.
     * The split is by cause rather than by call site so a deadlock keeps its retry, that being
     * exactly the transient casualty the retry was written for.
     *
     * <p>Each attempt asks {@link #reconciles} what it has to reconcile rather than being handed the
     * store's warm flag, for the reason stated there. A deadlock out of the first-graph refresh is
     * the retry's own reason for existing, so the retry has to be able to survive one.
     */
    private static Optional<Demotion> captureWithRetry(GraphitronModelStore shared,
                                                       GraphIdentity graph, CaptureBody body) {
        try {
            body.capture(shared.dsl(), reconciles(shared, graph));
            return Optional.empty();
        } catch (DataAccessException first) {
            if (timedOutOnALock(first)) {
                log().debug("the contended row's own failure", first);
                return Optional.of(new Demotion.Unavailable());
            }
            log().debug("shared fact store write failed; retrying once before recapturing in memory",
                first);
        }
        try {
            body.capture(shared.dsl(), reconciles(shared, graph));
            return Optional.empty();
        } catch (DataAccessException second) {
            return Optional.of(new Demotion.CaptureFailedTwice(graph.name(), second));
        }
    }

    /**
     * Whether an attempt at {@code graph} has rows of its own to reconcile: the store opened onto a
     * previous run's, or this graph stands committed in it already.
     *
     * <p><b>Asked per attempt rather than taken from the open.</b> {@link GraphitronModelStore#warm}
     * is fixed when the store opens, and it stood in for this question only while a capture was
     * all-or-nothing: a failed attempt rolled back, so the next attempt met the store the first one
     * found. That equivalence does not survive the first-graph refresh cadence, which commits this
     * graph's facts, its anchor row and its hand-written derivations before it refreshes. A retry
     * after a failed refresh therefore meets a partition its own first attempt wrote, and taking
     * warmth from the open would have it skip {@code StoreRefresh#prepare} and collide with itself on
     * the first key it re-inserts. The collision is a {@link DataAccessException} rather than a lock
     * timeout, so the retry would report it as a deterministic capture bug, which is a false
     * accusation about the run's own predecessor.
     *
     * <p>Visible beyond its one caller because that broken equivalence is what a test pins: the
     * store's warm flag and this predicate disagree the moment a capture on the same handle
     * commits, and no assertion over generated output can see the difference.
     */
    static boolean reconciles(GraphitronModelStore store, GraphIdentity graph) {
        return store.warm() || store.dsl().fetchExists(STORE_GRAPH,
            STORE_GRAPH.GRAPH_NAME.eq(graph.name()));
    }

    /**
     * Whether {@code failure} is a writer that ran out of lock budget, anywhere in its cause chain.
     * jOOQ wraps the driver's exception and H2 wraps its own store's, so the shape that survives
     * both is the JDBC contract: a lock timeout arrives as a {@link SQLTimeoutException} (H2 raises
     * error 50200, SQL state {@code HYT00}). Keying on that rather than on a message or a vendor
     * code also keeps a deadlock out, which arrives as a
     * {@link java.sql.SQLTransactionRollbackException} and keeps its retry.
     *
     * <p>That the type is enough holds only while no writer session carries a statement budget. An
     * expired {@link no.sikt.graphitron.model.boot.ReadBudget} raises the same
     * {@link SQLTimeoutException} with vendor code 57014, and this predicate would read it as lock
     * contention and demote the store to memory for a query that was merely too slow. The read side
     * therefore keys on the vendor code rather than the type; whoever gives a writer a budget has to
     * do the same here.
     */
    static boolean timedOutOnALock(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLTimeoutException) {
                return true;
            }
        }
        return false;
    }

    /** A fresh private store, captured cold, for a run that cannot have the shared one. */
    private static RunStore inMemory(GraphIdentity graph, CaptureBody body, Demotion reason) {
        return captureCold(GraphitronModelStore.open(), graph, body, reason);
    }

    /**
     * Reports the demotion, then captures into {@code store} as a cold one: a private store holds
     * nothing this run owns, whichever way it was arrived at.
     *
     * <p>Closes the store if the capture throws, the capture being infallible by construction and
     * a leaked connection being the worse way to learn that construction was wrong.
     */
    private static RunStore captureCold(GraphitronModelStore store, GraphIdentity graph,
                                        CaptureBody body, Demotion reason) {
        report(reason);
        try {
            body.capture(store.dsl(), false);
        } catch (RuntimeException | Error failure) {
            store.close();
            throw failure;
        }
        return new Demoted(store, graph, reason);
    }

    /**
     * Says once that the run was demoted, at the level the reason deserves. Everything a build
     * hears about losing the shared store is decided here, so a new reason cannot arrive without
     * someone deciding whether it is a warning.
     */
    private static void report(Demotion reason) {
        switch (reason) {
            // Not a warning. The caller named no directory, so this is the store it asked for, and
            // a build that warns about the state it requested teaches its reader to skip warnings.
            case Demotion.NoHomeGiven ignored -> log().debug(reason.message());
            case Demotion.CaptureFailedTwice failed -> log().warn(failed.message(), failed.failure());
            case Demotion.Unavailable ignored -> log().warn(reason.message());
            case Demotion.GraphOwnedElsewhere ignored -> log().warn(reason.message());
        }
    }

    /**
     * This file's logger, fetched per use rather than held in a field: a field on an interface is
     * public API, and a logger is not part of what this type offers. Demotions and sweep reports
     * happen at most once per run, so the lookup costs nothing worth a worse shape.
     */
    private static Logger log() {
        return LoggerFactory.getLogger(RunStore.class);
    }
}
