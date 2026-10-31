package no.sikt.graphitron.rewrite.derive;

import no.sikt.graphitron.model.test.CorpusStore;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The corpus sweeps in this module read one capture per JVM rather than one each. {@link CorpusStore}
 * counts the captures each population has had, and the count is one however many classes have asked
 * for it.
 *
 * <p>Not {@code @Isolated}, because the count belongs to one population and is exact whenever it is
 * read: it takes the lock a capture holds, so a class still waiting on the capture is waited out
 * rather than missed. A JVM-wide tally compared against a count of the populations asked for was
 * only right at rest, which made this the one class that ran alone, after every other.
 */
@PipelineTier
class CorpusCapturedOnceTest {

    @Test
    void theBareCorpusIsCapturedOncePerJvm() {
        CorpusStore corpus = CorpusStore.bare();
        corpus.reader().close();
        corpus.reader().close();
        assertThat(corpus.captures())
            .as("one capture however many readers asked; more means two classes were handed "
                + "two stores of one population")
            .isEqualTo(1);
        assertThat(CorpusStore.bare())
            .as("every class that asks for the bare corpus is handed the same population")
            .isSameAs(corpus);
    }
}
