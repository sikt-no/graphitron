package no.sikt.graphitron.lsp;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.fail;

/**
 * The one bound on a test in this module waiting for something it expects to happen: a wire
 * request's answer, a latch another thread counts down, a condition another thread makes true.
 *
 * <p>The bound is a hang guard, not a performance claim. A wait that succeeds returns as soon as
 * its answer arrives, so the bound costs nothing on a green run; it is set far enough above any
 * scheduling delay a loaded runner produces that tripping it means the thing waited for is not
 * coming. A failure names what was waited for rather than surfacing a bare
 * {@link TimeoutException}.
 *
 * <p>A wait asserting that something does <em>not</em> happen within a window is a different
 * shape: lengthening it costs real wall-clock, so it names its window as a constant of its own.
 * {@link FixedWaitGuardTest} keeps fixed literal bounds out of this module's tests.
 */
public final class HangGuard {

    /** How long a positive wait runs before the case calls it a hang. */
    public static final Duration BOUND = Duration.ofSeconds(60);

    private static final long POLL_MILLIS = 10;

    private HangGuard() {}

    /**
     * The value of {@code future}, waiting at most {@link #BOUND}; {@code what} is the request in
     * the reader's words, such as {@code "completion at 1:23"}.
     */
    public static <T> T answer(String what, Future<T> future)
        throws InterruptedException, ExecutionException {
        return answer(what, future, BOUND);
    }

    /** Waits for {@code latch} to reach zero, at most {@link #BOUND}. */
    public static void await(String what, CountDownLatch latch) throws InterruptedException {
        await(what, latch, BOUND);
    }

    /**
     * Polls {@code condition} until it holds, at most {@link #BOUND}. {@code what} is evaluated
     * only on failure, so it can carry the state observed at that moment.
     */
    public static void until(Supplier<String> what, BooleanSupplier condition)
        throws InterruptedException {
        until(what, condition, BOUND);
    }

    static <T> T answer(String what, Future<T> future, Duration bound)
        throws InterruptedException, ExecutionException {
        try {
            return future.get(bound.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            return fail("the server never answered " + what + " within " + render(bound), e);
        }
    }

    static void await(String what, CountDownLatch latch, Duration bound) throws InterruptedException {
        if (!latch.await(bound.toNanos(), TimeUnit.NANOSECONDS)) {
            fail(hung(what, bound));
        }
    }

    static void until(Supplier<String> what, BooleanSupplier condition, Duration bound)
        throws InterruptedException {
        long deadline = System.nanoTime() + bound.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() - deadline >= 0) {
                fail(hung(what.get(), bound));
            }
            Thread.sleep(POLL_MILLIS);
        }
    }

    private static String hung(String what, Duration bound) {
        return "waited " + render(bound) + " for " + what + ", which never came";
    }

    private static String render(Duration bound) {
        return bound.toMillis() % 1000 == 0 ? bound.toSeconds() + " s" : bound.toMillis() + " ms";
    }
}
