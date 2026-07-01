package no.sikt.graphitron.mcp.rag;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit-tier (R409): pins that {@link RagLogQuieting} matches its best-effort prose. Log-noise does
 * not warrant a pipeline/execution-tier test; the realistic pin is that the state mutations land
 * (Lucene JUL logger at {@code SEVERE}, DJL slf4j-simple property {@code error} plus its JUL level
 * {@code SEVERE}), that a second call is a no-op, and that the group-2 hint decision is the pure
 * function of module presence the demote-do-not-swallow design promises.
 */
class RagLogQuietingTest {

    @Test
    void quietingRaisesLuceneJulLevelAndSetsBothDjlLevers() {
        RagLogQuieting.quietRagWarmLogs(line -> {
        });

        assertThat(Logger.getLogger(RagLogQuieting.LUCENE_VECTORIZATION_LOGGER).getLevel())
            .isEqualTo(Level.SEVERE);
        assertThat(System.getProperty("org.slf4j.simpleLogger.log." + RagLogQuieting.DJL_TOKENIZER_LOGGER))
            .isEqualTo("error");
        assertThat(Logger.getLogger(RagLogQuieting.DJL_TOKENIZER_LOGGER).getLevel())
            .isEqualTo(Level.SEVERE);
    }

    @Test
    void callingTwiceIsANoOpAndDoesNotThrow() {
        RagLogQuieting.quietRagWarmLogs(line -> {
        });
        RagLogQuieting.quietRagWarmLogs(line -> {
        });

        // The state is the same after the second call: setting a property / JUL level twice is a no-op.
        assertThat(Logger.getLogger(RagLogQuieting.LUCENE_VECTORIZATION_LOGGER).getLevel())
            .isEqualTo(Level.SEVERE);
        assertThat(Logger.getLogger(RagLogQuieting.DJL_TOKENIZER_LOGGER).getLevel())
            .isEqualTo(Level.SEVERE);
        assertThat(System.getProperty("org.slf4j.simpleLogger.log." + RagLogQuieting.DJL_TOKENIZER_LOGGER))
            .isEqualTo("error");
    }

    @Test
    void incubatorHintIsSilentWhenTheModuleIsPresent() {
        assertThat(RagLogQuieting.incubatorHint(true)).isEmpty();
    }

    @Test
    void incubatorHintNamesTheFlagWhenTheModuleIsAbsent() {
        assertThat(RagLogQuieting.incubatorHint(false))
            .hasValueSatisfying(line ->
                assertThat(line).contains("--add-modules jdk.incubator.vector"));
    }

    @Test
    void quietingEmitsTheHintOnlyWhenTheModuleIsAbsent() {
        List<String> emitted = new ArrayList<>();
        RagLogQuieting.quietRagWarmLogs(emitted::add);

        // The emitted line count is the pure hint decision for the ambient module state: exactly the
        // hint when jdk.incubator.vector is absent, nothing when it is present.
        if (RagLogQuieting.incubatorVectorPresent()) {
            assertThat(emitted).isEmpty();
        } else {
            assertThat(emitted).singleElement()
                .asString().contains("--add-modules jdk.incubator.vector");
        }
    }
}
