package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import no.sikt.graphitron.model.test.InlineCaptures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * This module's tests capture fewer schemas inline over time, never more: every schema a test
 * captures is, or should be, a fact document, read from {@code FactGraphs} or captured into a store
 * of the test's own through {@code FactDocuments.capture}. {@link InlineCaptures} counts the capture
 * calls that state their schema some other way.
 *
 * <p>A ceiling rather than a roster, so conversions in several sessions do not collide. It may be
 * stale on the low side; what it may not do is rise. Lower it when you convert a site.
 */
@UnitTier
class InlineCaptureGateTest {

    /** Inline capture call sites at the ceiling's last lowering. */
    private static final int CEILING = 135;

    /** Where the arms are defined rather than called. */
    private static final Set<String> HARNESSES = Set.of("TestSchemaHelper.java", "BuiltStore.java", "GenerateTestSchemas.java",
        "InlineCaptureGateTest.java");

    @Test
    @DisplayName("no test starts capturing a schema it could state as a fact document")
    void inlineCapturesOnlyFall() {
        assertThat(InlineCaptures.in(Path.of("src/test/java"), HARNESSES))
            .as("capture calls stating their schema inline; state it as a fact document under "
                + "graphitron-model's src/test/resources/facts and read it from FactGraphs")
            .hasSizeLessThanOrEqualTo(CEILING);
    }
}
