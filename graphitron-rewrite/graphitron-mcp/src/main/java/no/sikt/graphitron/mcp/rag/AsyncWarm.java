package no.sikt.graphitron.mcp.rag;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;

/**
 * The generic async-warm harness (R372 D3): runs a loader callable on a background daemon thread and
 * drives a {@link WarmState} from {@link WarmState.Warming} to {@link WarmState.Ready} on success or
 * {@link WarmState.Failed} on any throwable. One harness covers both warms slice 8 drives: an
 * {@code AsyncWarm<Embedder>} for the shared bge load (heavy, shared across docs + catalog) and an
 * {@code AsyncWarm<EmbeddingStore>} per consumer index, so the embedder loads once and each index
 * warms independently.
 *
 * <p>The current state is exposed via a {@code volatile} read (the per-field visibility posture R361
 * already uses): the background thread publishes the terminal state, request threads read it without
 * locking. Nothing here ever touches the dev loop, so {@code graphitron:dev} reaches its watch loop
 * without waiting on any warm, and a RAG failure leaves dev running structured-only.
 *
 * <p><strong>Cross-warm ordering.</strong> An index warm that must build by embedding documents
 * depends on the embedder being {@code Ready}; its loader awaits the shared embedder warm on its own
 * background thread via {@link #await()} before embedding, so the dependency never reaches the dev
 * thread. An index warm that only loads a prebuilt index does not await the embedder at build time.
 *
 * @param <T> the warmed handle this harness produces
 */
public final class AsyncWarm<T> {

    private final String name;
    private final Callable<T> loader;
    private final CountDownLatch done = new CountDownLatch(1);

    /** Published by the warm thread, read by request threads; volatile gives the happens-before. */
    private volatile WarmState<T> state = new WarmState.Warming<>();

    /**
     * @param name   a short label for the daemon thread (diagnostics only)
     * @param loader the work to run off the dev thread: load the embedder, or build / load a store
     */
    public AsyncWarm(String name, Callable<T> loader) {
        this.name = name;
        this.loader = loader;
    }

    /**
     * Start the warm on a fresh background daemon thread. Created here, not in the constructor, so
     * construction never leaks {@code this} to another thread. Idempotent intent: call once per
     * harness.
     */
    public void start() {
        Thread thread = new Thread(this::run, "graphitron-warm-" + name);
        thread.setDaemon(true);
        thread.start();
    }

    private void run() {
        try {
            T handle = loader.call();
            state = new WarmState.Ready<>(handle);
        } catch (Throwable cause) {
            state = new WarmState.Failed<>(cause);
        } finally {
            done.countDown();
        }
    }

    /** The current warm state: {@code Warming} until the loader finishes, then the terminal value. */
    public WarmState<T> state() {
        return state;
    }

    /**
     * Block until the warm reaches a terminal state and return it: {@link WarmState.Ready} or
     * {@link WarmState.Failed}, never {@link WarmState.Warming}. This is the await affordance a
     * dependent build-warm sequences against (R372 D3): it switches exhaustively over the result and,
     * on {@code Failed}, maps the cause into its own {@code Failed} rather than blocking forever or
     * dereferencing a missing handle. There is no timeout: this runs on a daemon thread that never
     * touches the dev loop. An interrupt is mapped to a {@code Failed} so a dependent never observes
     * {@code Warming} from an await.
     */
    public WarmState<T> await() {
        try {
            done.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new WarmState.Failed<>(e);
        }
        return state;
    }
}
