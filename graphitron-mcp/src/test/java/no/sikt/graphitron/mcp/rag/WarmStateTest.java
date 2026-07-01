package no.sikt.graphitron.mcp.rag;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Seam-tier (R372): the {@link WarmState} degradation helper. It produces the standard wording for
 * both non-{@code Ready} states and rejects {@code Ready}. The switch in
 * {@link WarmState#degradationMessage} is exhaustive over the sealed permits with no {@code default},
 * so adding a fourth state would not compile until this helper handles it.
 */
class WarmStateTest {

    @Test
    void degradationMessageForWarmingPointsAtTheStructuredTools() {
        String message = WarmState.degradationMessage(new WarmState.Warming<Embedder>());
        assertThat(message).contains("warming").contains("structured tools");
    }

    @Test
    void degradationMessageForFailedNamesTheCauseAndKeepsTheStructuredTools() {
        var failed = new WarmState.Failed<EmbeddingStore>(new IllegalStateException("ONNX load failed"));
        String message = WarmState.degradationMessage(failed);
        assertThat(message)
            .contains("failed to load")
            .contains("IllegalStateException: ONNX load failed")
            .contains("structured tools");
    }

    @Test
    void degradationMessageForFailedWithNoCauseMessageStillReturnsACompactDescription() {
        var failed = new WarmState.Failed<Embedder>(new RuntimeException());
        assertThat(WarmState.degradationMessage(failed)).contains("RuntimeException");
    }

    @Test
    void degradationMessageRejectsReadyBecauseAReadyWarmHasNothingToDegradeTo() {
        var ready = new WarmState.Ready<Embedder>(new FakeEmbedder(384));
        assertThatThrownBy(() -> WarmState.degradationMessage(ready))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Ready");
    }
}
