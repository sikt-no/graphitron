package no.sikt.graphitron.rewrite.generators;

import com.palantir.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.util.List;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_OUTPUT_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Structural coverage for the facet emitters, at the method-surface level only
 * (per testing.adoc's ban on code-string assertions against generated method bodies, applied to
 * this class in the review's finding 4). What each fragment and the fetcher's facet plan
 * actually *do* is pinned behaviourally elsewhere: the sakila example's faceted fixture covers
 * the emitted shapes at the compilation tier (including the lifted-local base-fragment shape and
 * the cross-arg name collision), and {@code GraphQLQueryTest}'s {@code filmsFaceted} cases pin
 * the filter-minus-self semantics, the base's retention of non-facet predicates, the NULL-bucket
 * and scrub behaviour, and the failure degrade contract end to end.
 */
@UnitTier
class FacetEmitterTest {

    @Test
    void connectionHelper_resolvesNoFacetColumnByNameAtRuntime() {
        // The launcher binds each facet's typed column, source and count key; the helper keeps its
        // facet bucketing and declares no runtime by-name column lookup.
        var helper = no.sikt.graphitron.rewrite.generators.util.ConnectionHelperClassGenerator
            .generate(DEFAULT_OUTPUT_PACKAGE).getFirst();
        assertThat(methodNames(helper)).contains("facetBuckets").doesNotContain("facetColumn");
    }

    private static List<String> methodNames(TypeSpec spec) {
        return spec.methodSpecs().stream().map(m -> m.name()).toList();
    }
}
