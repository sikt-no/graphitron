package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.catalog.CatalogBuilder;
import no.sikt.graphitron.rewrite.catalog.ProjectionFor;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drift-prevention meta-test that pairs with
 * {@link CatalogBuilder#projectFieldClassification}'s compile-time exhaustive switch. The
 * switch enforces "every leaf has a projection arm"; this meta-test enforces "every leaf
 * has a payload-asserting test under {@link GraphitronSchemaBuilderTest}", so a future
 * contributor adding a new sealed leaf cannot land it without an accompanying sibling
 * projection assertion (or a typed {@link Exemption} on the registry row).
 *
 * <p>The obligation (domain, {@link ProjectionFor}-derived covered set, exemption map) is the
 * {@code ExemptionRegistry.LSP_PROJECTION} row. It is asserted here, at unit tier, rather than
 * by {@code ExemptionRegistryTest}'s pipeline-tier sweep: the covered set is annotation-derived
 * and needs no corpus classification, and keeping the assertion in this tier preserves the
 * fast-loop signal. Sibling to {@code VariantCoverageTest} on the classifier side; that one's
 * obligations walk the corpus and the {@link ClassificationCase} enum table, this one walks
 * methods annotated {@link ProjectionFor}.
 */
@UnitTier
class ProjectionCoverageTest {

    @Test
    void everySealedLeafHasAProjectionAssertion() {
        ExemptionRegistry.assertHonoured(ExemptionRegistry.LSP_PROJECTION);
    }

    @Test
    void projectionCoverageIsNotVacuous() {
        assertThat(ExemptionRegistry.LSP_PROJECTION.covered().get())
            .as("the @ProjectionFor scan must find a substantial covered set; near-empty means "
                + "the reflective walk broke, not that coverage vanished")
            .hasSizeGreaterThan(20);
    }
}
