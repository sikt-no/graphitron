package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.classifieddsl.CorpusDocuments;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.InputField;
import no.sikt.graphitron.rewrite.model.OutputField;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

/**
 * Meta-test: every sealed leaf of {@link GraphitronField} and {@link GraphitronType} must be
 * demonstrated at the grain its obligation walks, or carry a typed {@link Exemption}.
 *
 * <p>Coverage is split by who owns the verdict truth, one {@code ExemptionRegistry} obligation
 * per owner:
 *
 * <ul>
 *   <li><b>Output-field and type leaves</b> ({@link OutputField} leaves and every non-failure
 *       {@link GraphitronType} leaf): the spec-by-example corpus is the single source of truth;
 *       {@link CorpusDocuments#coveredLeaves()} is the covered set.</li>
 *   <li><b>Input-field leaves</b> ({@link InputField}): covered by the
 *       {@link GraphitronSchemaBuilderTest} enum truth table ({@link ClassificationCase}).</li>
 *   <li>The failure leaves ({@code UnclassifiedField} / {@code UnclassifiedType}) are out of
 *       scope for both: the corpus asserts successful classification only.</li>
 * </ul>
 *
 * <p>The obligation rows (domain, covered set, exemption map) are declared in
 * {@code ExemptionRegistry} and also swept by {@code ExemptionRegistryTest}'s parameterized
 * meta-test; the named tests here delegate to the same shared assertion so the two historical
 * entry points keep their identity and failure story. Complements
 * {@code GeneratorCoverageTest#everyGraphitronFieldLeafHasAKnownDispatchStatus} (generator
 * dispatch coverage) by asserting that classification itself is demonstrated for every leaf.
 */
@PipelineTier
class VariantCoverageTest {

    @Test
    void everyOutputFieldAndTypeLeafIsDemonstratedByTheCorpus() {
        ExemptionRegistry.assertHonoured(ExemptionRegistry.VARIANT_COVERAGE_OUTPUT);
    }

    @Test
    void everyInputFieldLeafHasAnEnumClassificationCase() {
        ExemptionRegistry.assertHonoured(ExemptionRegistry.VARIANT_COVERAGE_INPUT);
    }
}
