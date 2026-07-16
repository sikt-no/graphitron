package no.sikt.graphitron.mcp.rag;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Seam-tier: the {@link AsyncWarm} harness drives both warm instantiations to their terminal
 * {@link WarmState}, a read during warm sees {@link WarmState.Warming}, and the await affordance
 * returns the terminal value (never {@code Warming}) so a dependent build-warm maps an upstream
 * {@code Failed} into its own {@code Failed} rather than hanging. Both type parameters
 * ({@code WarmState<Embedder>} and {@code WarmState<EmbeddingStore>}) are exercised.
 */
class AsyncWarmTest {

    @Test
    void embedderWarmTransitionsWarmingToReadyAndAReadDuringWarmSeesWarming() throws Exception {
        var release = new CountDownLatch(1);
        var warm = new AsyncWarm<Embedder>("embedder", () -> {
            release.await();
            return new FakeEmbedder(384);
        });

        assertThat(warm.state()).isInstanceOf(WarmState.Warming.class);
        warm.start();
        // The loader is still blocked on the latch: a read during warm must see Warming.
        assertThat(warm.state()).isInstanceOf(WarmState.Warming.class);

        release.countDown();
        WarmState<Embedder> terminal = warm.await();

        if (terminal instanceof WarmState.Ready<Embedder> ready) {
            assertThat(ready.handle().dimension()).isEqualTo(384);
        } else {
            fail("expected Ready, got " + terminal);
        }
        // The volatile state read agrees with the terminal value the await returned.
        assertThat(warm.state()).isSameAs(terminal);
    }

    @Test
    void storeWarmTransitionsWarmingToReadyExercisingTheOtherTypeParameter() {
        var warm = new AsyncWarm<EmbeddingStore>("index", () -> LuceneEmbeddingStore.inMemory(384));
        warm.start();

        WarmState<EmbeddingStore> terminal = warm.await();
        if (terminal instanceof WarmState.Ready<EmbeddingStore> ready) {
            ready.handle().close();
        } else {
            fail("expected Ready, got " + terminal);
        }
    }

    @Test
    void aThrowingLoaderTransitionsToFailedCarryingTheCause() {
        var warm = new AsyncWarm<Embedder>("boom", () -> {
            throw new IllegalStateException("model load blew up");
        });
        warm.start();

        WarmState<Embedder> terminal = warm.await();
        if (terminal instanceof WarmState.Failed<Embedder> failed) {
            assertThat(failed.cause())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("model load blew up");
        } else {
            fail("expected Failed, got " + terminal);
        }
    }

    @Test
    void aDependentBuildWarmMapsAnUpstreamFailedIntoItsOwnFailedRatherThanHanging() {
        // The shared embedder warm fails to load.
        var embedderWarm = new AsyncWarm<Embedder>("embedder", () -> {
            throw new RuntimeException("ONNX unavailable");
        });
        embedderWarm.start();

        // A dependent index build-warm awaits the embedder on its own thread and switches
        // exhaustively over the terminal state: a Failed upstream becomes this warm's own Failed.
        var indexWarm = new AsyncWarm<EmbeddingStore>("index", () -> {
            WarmState<Embedder> upstream = embedderWarm.await();
            return switch (upstream) {
                case WarmState.Ready<Embedder> ready ->
                    LuceneEmbeddingStore.inMemory(ready.handle().dimension());
                case WarmState.Failed<Embedder> failed ->
                    throw new IllegalStateException("embedder warm failed, cannot build index", failed.cause());
                case WarmState.Warming<Embedder> ignored ->
                    throw new AssertionError("await must never return Warming");
            };
        });
        indexWarm.start();

        WarmState<EmbeddingStore> terminal = indexWarm.await();
        if (terminal instanceof WarmState.Failed<EmbeddingStore> failed) {
            assertThat(failed.cause())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("embedder warm failed");
            assertThat(failed.cause().getCause()).hasMessage("ONNX unavailable");
        } else {
            fail("expected the dependent warm to resolve to Failed, got " + terminal);
        }
    }
}
