package no.sikt.graphitron.rewrite.capture;

import no.sikt.graphitron.model.boot.GraphitronModelStore;
import no.sikt.graphitron.model.read.StoreHandle;
import no.sikt.graphitron.rewrite.RewriteContext;
import no.sikt.graphitron.rewrite.derive.StoreDetections;

import java.nio.file.Path;
import java.util.Objects;

/**
 * A place to capture into and read back, handed to whoever runs a pass. The generator asks for a
 * capture and gets a {@link StoreHandle} to question; that there is a store behind it, where it
 * lives, whether it is shared with the rest of the workspace and what happens when it cannot be,
 * are all this type's business and none of the caller's.
 *
 * <p><b>Why a seam and not a call.</b> The generator used to name the entry point and reach the
 * store's home off its own context, which made "the generator reads facts and writes none" a
 * property of what its code happened to do rather than of what it could reach. With the store on
 * the far side of this interface, a pass cannot open one, cannot say where one lives, and cannot
 * hold one past the reads it asked for, because it never has one to hold. Its caller decides all
 * three by choosing the arm.
 *
 * <p><b>Two arms, differing only in the store's lifetime.</b> {@link #holding} keeps one store from
 * the first capture until {@link #close}, which is what a goal wants: one store per invocation,
 * serving every pass the goal runs, so a session stops opening a database per round.
 * {@link #forContext} opens and closes one around each capture, which is what a caller with no
 * lifetime to offer wants, and is the behaviour every caller had before the arms existed. Neither
 * changes a row: what a caller picks is how long a connection is held, never what gets written or
 * what a read answers.
 */
public sealed interface CapturePort extends AutoCloseable {

    /**
     * Captures {@code request}, runs the store-backed detections over what it wrote, and hands
     * {@code after} the store plus those detections. The order is the only sequencing imposed: what
     * the caller does inside {@code after} is the caller's, capture neither knowing nor deciding
     * what follows it.
     */
    <T> T captureAndRead(CaptureRequest request, AfterCapture<T> after);

    /**
     * {@link #captureAndRead} for a pass with nothing to read back: the failure arms, where a stage
     * refused the document and the point of capturing at all is that the author's facts are still
     * true and worth writing down before the build fails.
     */
    default void capture(CaptureRequest request) {
        captureAndRead(request, (store, detections) -> null);
    }

    /** Gives up whatever store this port was holding. Idempotent. */
    @Override
    void close();

    /**
     * What a caller does with the captured store while it is still open: the detection product
     * every family writes into, plus the {@link StoreHandle} the same store answers reads through.
     *
     * <p>A continuation rather than a return value because the handle's validity is the port's to
     * bound. The {@link #forContext} arm closes its store when the call returns, so a handle that
     * escaped would answer nothing; keeping the reads inside the call is what makes the two arms
     * interchangeable to a caller.
     */
    @FunctionalInterface
    interface AfterCapture<T> {
        T read(StoreHandle store, StoreDetections detections);
    }

    /**
     * A port over one store, opened on the first capture and held until {@link #close}. What a
     * Maven goal builds and hands to every pass it runs.
     *
     * <p>Opened lazily because the store cannot be opened without the capture that fills it: the
     * shared file may refuse a write and demote the run, which is only knowable once the write has
     * been attempted, and {@link RunStore#forRun} therefore takes the capture as its argument. So a
     * goal owns the store's lifetime by owning this port, not by holding a store of its own.
     *
     * @param storeDirectory the workspace's store home, or {@code null} for a caller with none
     */
    static CapturePort holding(Path storeDirectory) {
        return new Held(storeDirectory, null);
    }

    /**
     * {@link #holding} over a store the caller already has open, which the port captures into and
     * never closes. What a dev session builds: its language server and MCP readers are on that
     * store, so a pass that captured anywhere else would leave them answering from rows no pass
     * wrote.
     *
     * <p>A refusal still demotes, and then the port is on a private store of its own while the
     * lent one stays exactly as its owner left it. That is the same outcome a session had when each
     * pass opened its own store, and it is why the lender keeps closing what it opened.
     */
    static CapturePort over(GraphitronModelStore lent) {
        Objects.requireNonNull(lent, "lent");
        return new Held(null, lent);
    }

    /**
     * A port that opens and closes a store around each capture, reading the store's home off
     * {@code ctx}. For a caller that runs one pass and has no lifetime to lend it, which is every
     * caller that constructs a generator without handing it a port.
     */
    static CapturePort forContext(RewriteContext ctx) {
        Objects.requireNonNull(ctx, "ctx");
        return new PerRun(ctx.storeDirectory());
    }

    /**
     * {@link #forContext} for a caller that holds the store's home directly rather than a context
     * to read it off.
     */
    static CapturePort perRun(Path storeDirectory) {
        return new PerRun(storeDirectory);
    }

    /** The store-per-capture arm. Holds nothing between calls, so {@link #close} has nothing to do. */
    record PerRun(Path storeDirectory) implements CapturePort {

        @Override
        public <T> T captureAndRead(CaptureRequest request, AfterCapture<T> after) {
            Objects.requireNonNull(request, "request");
            Objects.requireNonNull(after, "after");
            try (RunStore store = RunStore.forRun(storeDirectory, request.graph(),
                    request.body())) {
                return FactCapture.read(store.handle(), request.classified(), after);
            }
        }

        @Override
        public void close() {
        }
    }

    /**
     * The one-store arm. The first capture decides which store this port ended on and a later one
     * can still lose the shared file, so the field is reassigned from {@link RunStore#recapture}
     * rather than fixed at the first call; {@link #close} gives back whichever store it ended on.
     *
     * <p>Synchronised because the store is one connection pool and this arm exists to be shared:
     * a dev session runs its passes off a debounce, and two passes that ever did overlap would
     * otherwise interleave two captures of the same partition on one handle. Waiting is the right
     * answer there, and it costs nothing when they do not overlap.
     */
    final class Held implements CapturePort {

        private final Path storeDirectory;
        private final GraphitronModelStore lent;
        private RunStore store;

        private Held(Path storeDirectory, GraphitronModelStore lent) {
            this.storeDirectory = storeDirectory;
            this.lent = lent;
        }

        @Override
        public synchronized <T> T captureAndRead(CaptureRequest request, AfterCapture<T> after) {
            Objects.requireNonNull(request, "request");
            Objects.requireNonNull(after, "after");
            store = store == null ? first(request) : store.recapture(request.body());
            return FactCapture.read(store.handle(), request.classified(), after);
        }

        /**
         * The capture that decides which store this port is on: into the lent store when there is
         * one, otherwise into whichever store the home yields. Only the first capture asks, every
         * later one recapturing into the answer.
         */
        private RunStore first(CaptureRequest request) {
            return lent == null
                ? RunStore.forRun(storeDirectory, request.graph(), request.body())
                : RunStore.forRunOn(lent, request.graph(), request.body());
        }

        @Override
        public synchronized void close() {
            if (store != null) {
                store.close();
                store = null;
            }
        }
    }
}
