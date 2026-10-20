package no.sikt.graphitron.lsp;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A wait that never ends fails naming what it waited for, and one that is already satisfied returns
 * at once. The failing cases go through the overloads that take the bound, so they run in
 * milliseconds rather than {@link HangGuard#BOUND}.
 */
class HangGuardTest {

    private static final Duration SHORT = Duration.ofMillis(50);

    @Test
    void answerThatNeverComesNamesTheRequest() {
        assertThatThrownBy(() -> HangGuard.answer("completion at 1:23", new CompletableFuture<>(), SHORT))
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("the server never answered completion at 1:23 within 50 ms");
    }

    @Test
    void latchNobodyCountsDownNamesTheEvent() {
        assertThatThrownBy(() -> HangGuard.await("the drain to reach its publish", new CountDownLatch(1), SHORT))
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("waited 50 ms for the drain to reach its publish, which never came");
    }

    @Test
    void conditionThatNeverHoldsCarriesTheStateSeenAtFailure() {
        var polls = new int[1];
        assertThatThrownBy(() -> HangGuard.until(
            () -> "a publish, after " + (polls[0] > 1 ? "several" : "too few") + " polls",
            () -> { polls[0]++; return false; },
            SHORT))
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("waited 50 ms for a publish, after several polls, which never came");
    }

    @Test
    void satisfiedWaitsReturnAtOnce() throws Exception {
        assertThat(HangGuard.answer("initialize", CompletableFuture.completedFuture("ready"))).isEqualTo("ready");
        HangGuard.await("an open gate", new CountDownLatch(0));
        HangGuard.until(() -> "a true condition", () -> true);
    }

    @Test
    void failedRequestPropagatesAsItDid() {
        assertThatThrownBy(() -> HangGuard.answer("hover",
            CompletableFuture.failedFuture(new IllegalStateException("boom"))))
            .isInstanceOf(java.util.concurrent.ExecutionException.class)
            .hasCauseInstanceOf(IllegalStateException.class);
    }
}
