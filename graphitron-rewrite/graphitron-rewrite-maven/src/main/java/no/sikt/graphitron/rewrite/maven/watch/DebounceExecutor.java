package no.sikt.graphitron.rewrite.maven.watch;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Coalesces a burst of triggers into a single delayed task. Each {@link #schedule(Runnable)}
 * call cancels any pending task and reschedules it {@code windowMs} milliseconds in the future,
 * so a stream of events fired closer than the window apart fires the task exactly once after
 * the burst settles.
 *
 * <p>Single-threaded by design: the watch loop is the only producer, and serialising trigger
 * runs is what gives the watch goal its no-overlap guarantee.
 */
public final class DebounceExecutor implements AutoCloseable {

    private final ScheduledExecutorService executor;
    private final long windowMs;
    private ScheduledFuture<?> pending;

    public DebounceExecutor(long windowMs) {
        this.windowMs = windowMs;
        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "graphitron-watch-debounce");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Schedules {@code task} to run {@code windowMs} from now, cancelling any previously
     * scheduled task that has not yet started. A task already running is not interrupted; the
     * incoming trigger is queued behind it on the same single-thread executor.
     */
    public synchronized void schedule(Runnable task) {
        if (pending != null) {
            pending.cancel(false);
        }
        pending = executor.schedule(task, windowMs, TimeUnit.MILLISECONDS);
    }

    @Override
    public synchronized void close() {
        if (pending != null) {
            pending.cancel(false);
            pending = null;
        }
        executor.shutdownNow();
        try {
            executor.awaitTermination(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
