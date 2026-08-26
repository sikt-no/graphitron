package no.sikt.graphql.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("PartialBatchFailureException")
class PartialBatchFailureExceptionTest {

    private static final BatchItemFailure A_FAILURE =
            new BatchItemFailure(List.of("mutation", "in", "1"), new IllegalStateException("no such customer"));

    @Test
    @DisplayName("carries at least one failure, since a batch with none did not partially fail")
    void shouldRejectNoFailures() {
        assertThatThrownBy(() -> new PartialBatchFailureException("payload", List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one failure");
    }

    @Test
    @DisplayName("keeps the payload built from the elements that succeeded")
    void shouldKeepThePayload() {
        var exception = new PartialBatchFailureException("payload", List.of(A_FAILURE));

        assertThat(exception.getPayload()).isEqualTo("payload");
        assertThat(exception.getFailures()).containsExactly(A_FAILURE);
    }

    @Test
    @DisplayName("reports the first element's own exception as its cause, so an unhandled one is still logged")
    void shouldCarryTheFirstCause() {
        var exception = new PartialBatchFailureException("payload", List.of(A_FAILURE));

        assertThat(exception.getCause()).isSameAs(A_FAILURE.cause());
        assertThat(exception.getMessage()).contains("1 element(s)");
    }
}
