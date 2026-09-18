package no.sikt.graphitron.rewrite.lint;

import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import no.sikt.graphitron.model.lint.LintRule;

/**
 * The rule catalogue, against the producers that are supposed to exist for it.
 *
 * <p>This was a registry's coverage while the engine rules were visitors and a registry wired them
 * up. Nothing wires them now, the rules being arms of a view, so the claim that every engine rule is
 * implemented moved to the cases beside them, where it is the stronger statement: a rule is covered
 * when a corpus written to offend it draws a row, not when a class is on a list.
 *
 * <p>What is left here is about the other three sources, which are producers this enum names and
 * something elsewhere emits, and about the ids themselves.
 */
@UnitTier
class LintRuleCatalogueTest {




    @Test
    void everyClassifierAdvisoryRuleExists() {
        // The classifier-owned advisories are tagged onto BuildWarning.LintFinding at their
        // emit sites (TypeBuilder / FieldBuilder); this pins they remain enumerated as CLASSIFIER.
        var classifier = Arrays.stream(LintRule.values())
            .filter(r -> r.source() == LintRule.Source.CLASSIFIER)
            .map(LintRule::id)
            .toList();
        assertThat(classifier).containsExactlyInAnyOrder(
            "splitquery-redundant-on-record-parent",
            "splitquery-redundant-on-discriminated-interface-child",
            "redundant-record-directive",
            "asconnection-same-table-pk-in");
    }

    @Test
    void everyCodegenAdvisoryRuleExists() {
        // The codegen advisories are emitted at report assembly from whole-build facts: the
        // <sessionState> config (SessionStateWarnings) and the resolved dependency versions
        // (DependencyVersionWarnings). Neither a visitor nor a classifier site; this pins the CODEGEN
        // set so a new one is a deliberate registry edit, mirroring the classifier assertion above.
        var codegen = Arrays.stream(LintRule.values())
            .filter(r -> r.source() == LintRule.Source.CODEGEN)
            .map(LintRule::id)
            .toList();
        assertThat(codegen).containsExactlyInAnyOrder(
            "no-session-state",
            "graphql-java-version-lag",
            "jooq-version-lag");
    }

    @Test
    void everyDerivedProducerRuleExists() {
        // The derived-relation producers answer from the store's own derived views and are folded
        // in at report assembly, beside the codegen advisories and told apart from them by being a
        // different producer with its own completeness assertion. This pins the DERIVED set so a
        // new one is a deliberate registry edit, mirroring the two assertions above it.
        var derived = Arrays.stream(LintRule.values())
            .filter(r -> r.source() == LintRule.Source.DERIVED)
            .map(LintRule::id)
            .toList();
        assertThat(derived).containsExactlyInAnyOrder("reference-path-fans-out");
    }



    @Test
    void ruleIdsAreUniqueAndKebabCase() {
        List<String> ids = Arrays.stream(LintRule.values()).map(LintRule::id).toList();
        assertThat(ids).doesNotHaveDuplicates();
        assertThat(ids).allSatisfy(id ->
            assertThat(id).matches("[a-z0-9]+(-[a-z0-9]+)*"));
    }
}
