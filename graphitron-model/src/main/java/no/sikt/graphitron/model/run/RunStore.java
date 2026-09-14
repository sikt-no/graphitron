package no.sikt.graphitron.model.run;

import no.sikt.graphitron.model.boot.GraphitronModelStore;
import no.sikt.graphitron.model.boot.StoreUnavailableException;
import no.sikt.graphitron.model.read.StoreHandle;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

import static no.sikt.graphitron.model.Tables.STORE_GRAPH;

/**
 * The store a capture run got, open, and who closes it: one this run opened and owns, or one its
 * caller opened and will close.
 *
 * <p><b>A run that cannot have the store it asked for fails.</b> It does not capture somewhere else
 * and carry on. The three ways it can happen are three different things for a person to go and do,
 * and each says which: the home cannot be written, a file at the stamped path was moved or damaged
 * by hand, or the graph name is recorded against another checkout. {@link GraphitronModelStore}
 * raises the first two as it opens; this file raises the third, where the store is open and the row
 * is readable. A run with no store directory at all is not one of them: it asked for a private
 * in-memory store and gets one.
 *
 * <p><b>What that replaced, and why the trade turned out to be the wrong way round.</b> Every way
 * of losing the shared file used to end in a private in-memory store holding the same rows, on the
 * reasoning that a cache is never allowed to cost more than a cache is worth. The content half of
 * that holds and is pinned: a cold store answers the reads a warm one would. What it got wrong is
 * that not every arm was cache trouble. A write the shared file refused twice was called a
 * deterministic capture bug in its own message and then swallowed anyway, and one such bug fired on
 * every warm pass on the vocabulary graphitron itself ships, unseen, because the fallback capture
 * is cold and the defect was warm-only. A mechanism that hides the failures worth knowing about in
 * order to save a head start is not paying for itself.
 *
 * <p>So the store's identity is settled when it opens, which is why {@link #forRun} answers before
 * the capture rather than after it. A write that fails now fails.
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

    /**
     * Captures into the store this value already holds: the second and later captures of a caller
     * that keeps one store across a session's passes rather than opening one per pass.
     *
     * <p>Nothing is returned and nothing can be swapped underneath the caller. A store that has
     * served one capture serves the next, and a capture that fails propagates, there being no
     * second store to try and nothing a cold retry would establish. Through the same rule the
     * first capture took, so a session's second round is told what its first would have been.
     */
    default void recapture(CaptureBody body) {
        GraphitronStore.capture(graph().name(),
            () -> body.capture(store().dsl(), reconciles(store(), graph())));
    }

    /** The query surface the run's own readers ask through. */
    default StoreHandle handle() {
        return new StoreHandle(store().dsl(), graph().name());
    }

    @Override
    default void close() {
        GraphitronModelStore store = store();
        Optional<Path> home = store.location();
        store.close();
        // Said where the sweep's report is said, and for the same reason: a build that spends a
        // second of its wall-clock inside the store should name the store rather than leave a
        // developer to find it in a thread dump. Only the handle that actually compacted has one.
        home.ifPresent(at -> store.compaction()
            .flatMap(compaction -> compaction.report(at))
            .ifPresent(log()::info));
    }

    /** The run captured into a store it opened and closes: the shared file, or a private one. */
    record Owned(GraphitronModelStore store, GraphIdentity graph) implements RunStore {
        public Owned {
            Objects.requireNonNull(store, "store");
            Objects.requireNonNull(graph, "graph");
        }
    }

    /**
     * The run captured into a store its caller opened and will close: a session that runs many
     * passes and hands each of them the one store it holds for its own readers. Structurally
     * separate from {@link Owned} because the only thing that differs is who closes, and that is
     * exactly the thing a caller must not get wrong.
     */
    record Borrowed(GraphitronModelStore store, GraphIdentity graph) implements RunStore {
        public Borrowed {
            Objects.requireNonNull(store, "store");
            Objects.requireNonNull(graph, "graph");
        }

        /** Nothing: the lender closes what the lender opened. */
        @Override
        public void close() {
        }
    }

    /**
     * What a run writes into the store it got. The capture's content is the caller's, the store's
     * lifetime is this file's, and the {@code warm} flag is the one thing that crosses.
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
     * Opens the store this run captures into and fills it with {@code body}.
     *
     * <p>With a directory the store is the shared file under it, so the run starts from the
     * previous runs' rows and rewrites only what it owns and cannot prove unchanged; without one it
     * is a private in-memory database, which is what every caller with no home to give should get.
     * The two differ in cost, never in content: a warm store is refreshed to exactly the rows a
     * cold load would have produced.
     *
     * <p>The capture is still passed in rather than run by the caller, which is residue: it was
     * here so that a write refused twice could be answered with a different store, and nothing
     * answers a refused write any more. It leaves with the port that hands it over.
     *
     * @param storeDirectory the workspace's store home, or {@code null} for a caller with none
     * @param graph          the coordinate this run writes under
     * @param body           the capture itself, called exactly once
     * @throws StoreUnavailableException if the store cannot be opened, or if {@code graph} is
     *         recorded in it against a different base directory
     */
    static RunStore forRun(Path storeDirectory, GraphIdentity graph, CaptureBody body) {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(body, "body");
        if (storeDirectory == null) {
            // Not a fallback and not worth a warning: a caller that named no directory asked for
            // this store, and a build that warns about the state it requested teaches its reader
            // to skip warnings.
            log().debug("no fact store directory was configured for this run, so its facts are "
                + "captured in memory.");
            return capture(new Owned(GraphitronModelStore.open(), graph), body);
        }
        GraphitronModelStore shared = GraphitronModelStore.openAt(storeDirectory);
        // Whatever the open released from the cache home, said once. A run that quietly deletes
        // gigabytes out of a person's cache home should say so, and this is where an ordinary build
        // hears it: the store's once-per-JVM sweep guard makes the reporter whichever opener ran
        // first, which on a build is this one.
        shared.reaped().report(storeDirectory).ifPresent(log()::info);
        return capture(owned(shared, graph), body);
    }

    /**
     * {@link #forRun} for a caller that already holds an open store and wants this run to capture
     * into it: a dev session, whose readers are on that store and whose passes should write where
     * those readers look rather than each opening a database of their own.
     *
     * <p>The ownership check is asked of a warm lent store exactly as it is of a warm one opened
     * here, so a session whose graph name is recorded against another checkout is refused the same
     * way. What differs is only who closes.
     *
     * @param lent  the caller's open store, which this run captures into and never closes
     * @param graph the coordinate this run writes under
     * @param body  the capture itself, called exactly once
     */
    static RunStore forRunOn(GraphitronModelStore lent, GraphIdentity graph, CaptureBody body) {
        Objects.requireNonNull(lent, "lent");
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(body, "body");
        refuseIfOwnedElsewhere(lent, graph);
        return capture(new Borrowed(lent, graph), body);
    }

    /**
     * The opened store checked and wrapped, closing it if the check refuses: a store this run
     * opened and will not use is this run's to give back, and a leaked connection would hold the
     * file against the next attempt at exactly the moment a person is being told to try again.
     */
    private static RunStore owned(GraphitronModelStore shared, GraphIdentity graph) {
        try {
            refuseIfOwnedElsewhere(shared, graph);
        } catch (RuntimeException | Error refusal) {
            shared.close();
            throw refusal;
        }
        return new Owned(shared, graph);
    }

    /**
     * Fills {@code run}'s store, closing it if the capture throws. Capture is infallible by
     * construction and a leaked connection is the worse way to learn that construction was wrong;
     * a borrowed store closes to nothing, which is correct, the lender still owning it.
     */
    private static RunStore capture(RunStore run, CaptureBody body) {
        try {
            GraphitronStore.capture(run.graph().name(),
                () -> body.capture(run.store().dsl(), reconciles(run.store(), run.graph())));
        } catch (RuntimeException | Error failure) {
            run.close();
            throw failure;
        }
        return run;
    }

    /**
     * Refuses a run whose graph name is recorded in this store against a different base directory.
     * Asked only of a warm store, a cold one having no row to disagree with, and asked here rather
     * than in the Maven goal, which never reads the store.
     *
     * <p>The message names both directories and the setting that separates them, because this is
     * the refusal a consumer can fix outright.
     */
    private static void refuseIfOwnedElsewhere(GraphitronModelStore store, GraphIdentity graph) {
        if (!store.warm()) {
            return;
        }
        String recorded = store.dsl().select(STORE_GRAPH.BASE_DIR).from(STORE_GRAPH)
            .where(STORE_GRAPH.GRAPH_NAME.eq(graph.name()))
            .fetchOne(0, String.class);
        if (recorded == null || recorded.equals(graph.baseDir().toString())) {
            return;
        }
        throw new StoreUnavailableException(("graphitron: graph '%s' is already recorded in the "
            + "fact store for %s, but this run's base directory is %s. Set <graphName> so the two "
            + "modules stop claiming one name, then run again.")
            .formatted(graph.name(), recorded, graph.baseDir()));
    }

    /**
     * Whether an attempt at {@code graph} has rows of its own to reconcile: the store opened onto a
     * previous run's, or this graph stands committed in it already.
     *
     * <p><b>Asked per attempt rather than taken from the open.</b> {@link GraphitronModelStore#warm}
     * is fixed when the store opens, and it stood in for this question only while a capture was
     * all-or-nothing: a failed attempt rolled back, so the next attempt met the store the first one
     * found. That equivalence does not survive the first-graph refresh cadence, which commits this
     * graph's facts, its anchor row and its hand-written derivations before it refreshes. A later
     * capture on the same handle therefore meets a partition an earlier one wrote, and taking
     * warmth from the open would have it skip {@code StoreRefresh#prepare} and collide with itself
     * on the first key it re-inserts.
     *
     * <p>Visible beyond its callers because that broken equivalence is what a test pins: the
     * store's warm flag and this predicate disagree the moment a capture on the same handle
     * commits, and no assertion over generated output can see the difference.
     */
    static boolean reconciles(GraphitronModelStore store, GraphIdentity graph) {
        return store.warm() || store.dsl().fetchExists(STORE_GRAPH,
            STORE_GRAPH.GRAPH_NAME.eq(graph.name()));
    }

    /**
     * This file's logger, fetched per use rather than held in a field: a field on an interface is
     * public API, and a logger is not part of what this type offers. Sweep reports and the
     * in-memory note happen at most once per run, so the lookup costs nothing worth a worse shape.
     */
    private static Logger log() {
        return LoggerFactory.getLogger(RunStore.class);
    }
}
