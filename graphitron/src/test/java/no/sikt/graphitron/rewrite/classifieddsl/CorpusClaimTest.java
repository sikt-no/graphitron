package no.sikt.graphitron.rewrite.classifieddsl;

import no.sikt.graphitron.model.test.CorpusDocuments;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That the parameterized corpus test runs over every document the corpus holds.
 *
 * <p>Here rather than beside the loader, which is {@code graphitron-model}'s and has no opinion
 * about who reads it. Which test claims a document is a fact about this module's suite, so the
 * claim is asserted where the claimant lives.
 */
@UnitTier
class CorpusClaimTest {

    @Test
    void theCorpusTestClaimsEveryLoadedDocument() {
        assertThat(ClassifiedDslTest.corpus().toList())
            .as("the parameterized corpus test must run over every loaded document; a document that "
                + "loads and is never asserted on is a fixture the build believes it checked")
            .containsExactlyElementsOf(CorpusDocuments.documents());
    }
}
