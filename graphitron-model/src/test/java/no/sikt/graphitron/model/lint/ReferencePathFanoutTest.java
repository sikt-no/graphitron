package no.sikt.graphitron.model.lint;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The fan-out producer's decode of the view's verdict vocabulary.
 *
 * <p>What each verdict means is witnessed against captured schemas by the
 * {@code field-path-fanout} fact document; which verdicts word a finding, what it says, and which
 * fields and types it stays away from is witnessed end to end by the generator's pipeline test.
 */
class ReferencePathFanoutTest {

    /**
     * The decode is total over the view's vocabulary and drifts loudly. The view's arms and this
     * enum have to move together, so a value the enum does not name is a build bug rather than a
     * row to skip.
     */
    @Test
    void anUnknownVerdictIsDriftAndThrows() {
        assertThat(Arrays.stream(ReferencePathFanout.Verdict.values())
            .map(v -> ReferencePathFanout.Verdict.of(v.name())))
            .containsExactly(ReferencePathFanout.Verdict.values());
        assertThatThrownBy(() -> ReferencePathFanout.Verdict.of("SOMETHING_ELSE"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("SOMETHING_ELSE")
            .hasMessageContaining("must move together");
    }
}
