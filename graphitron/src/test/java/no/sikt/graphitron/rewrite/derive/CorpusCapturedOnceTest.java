package no.sikt.graphitron.rewrite.derive;

import no.sikt.graphitron.model.test.CorpusStore;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import java.net.URISyntaxException;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The corpus sweeps in this module read one capture per population per JVM rather than one each.
 * {@link CorpusStore} counts the populations it was asked for and the captures it ran, and the two
 * are equal exactly while no population was captured twice.
 *
 * <p>{@code @Isolated} because the equality is only exact at rest: a class still waiting on a
 * population's capture has asked for it and not yet been counted. JUnit runs isolated classes
 * after every concurrent one has finished, so this reads the whole run's counts rather than a
 * fraction of them.
 */
@PipelineTier
@Isolated("reads CorpusStore's JVM-wide counts, which are exact only while nothing is capturing")
class CorpusCapturedOnceTest {

    @Test
    void eachPopulationIsCapturedOncePerJvm() {
        // Both populations this module's sweeps read, so the equality is over at least those.
        CorpusStore.bare().reader().close();
        CorpusStore.over(testClassRoot()).reader().close();
        assertThat(CorpusStore.populations())
            .as("the bare corpus and the corpus over this module's test classes")
            .isGreaterThanOrEqualTo(2);
        assertThat(CorpusStore.initializations())
            .as("one capture per population asked for; more means a population was captured twice")
            .isEqualTo(CorpusStore.populations());
    }

    private static Path testClassRoot() {
        try {
            return Path.of(CorpusCapturedOnceTest.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException("the test classes are not on a file path", e);
        }
    }
}
