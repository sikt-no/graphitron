package no.sikt.graphitron.rewrite.maven.watch;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
}
