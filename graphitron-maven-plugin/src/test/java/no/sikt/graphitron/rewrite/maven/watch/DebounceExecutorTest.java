package no.sikt.graphitron.rewrite.maven.watch;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class DebounceExecutorTest {

    @Test
    void burstOfEvents_runsTaskOnce() throws Exception {
        var debounce = new DebounceExecutor(100);
        var latch = new CountDownLatch(1);
        var fired = new AtomicInteger();
        Runnable task = () -> {
            fired.incrementAndGet();
            latch.countDown();
        };

        try {
            debounce.schedule(task);
            Thread.sleep(20);
            debounce.schedule(task);
            Thread.sleep(20);
            debounce.schedule(task);

            assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(150);
            assertThat(fired.get()).isEqualTo(1);
        } finally {
            debounce.close();
        }
    }

    @Test
    void close_cancelsPendingTask() throws Exception {
        var debounce = new DebounceExecutor(100);
        var fired = new AtomicInteger();

        debounce.schedule(fired::incrementAndGet);
        debounce.close();

        Thread.sleep(300);
        assertThat(fired.get()).isZero();
    }

    /**
     * A running task regenerates and writes the fact store, so closing lets it finish rather than
     * interrupting it inside the store's file I/O.
     */
    @Test
    void close_letsARunningTaskFinishUninterrupted() throws Exception {
        var debounce = new DebounceExecutor(0);
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var finished = new CountDownLatch(1);
        var interrupted = new AtomicBoolean();

        debounce.schedule(() -> {
            started.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                interrupted.set(true);
            }
            interrupted.compareAndSet(false, Thread.currentThread().isInterrupted());
            finished.countDown();
        });
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

        debounce.close();
        release.countDown();

        assertThat(finished.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(interrupted).as("the running task was interrupted").isFalse();
    }
}
