package no.sikt.graphitron.rewrite.lint;

import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drift guard for the lint registry and its node-kind partition (R398), mirroring the
 * {@code VariantCoverageTest} / {@code EdgeCoverageTest} no-silent-default pattern: every engine rule
 * is registered to exactly one visitor, no classifier advisory leaks into the registry, and the
 * subscribed and not-linted kind sets partition {@link LintNodeKind} with no overlap and no gap.
 */
@UnitTier
class LintRuleRegistryCoverageTest {

    @Test
    void everyEngineRuleIsRegisteredToExactlyOneVisitor() {
        var registered = LintRules.builtIn().stream().map(LintVisitor::rule).toList();
        var engineRules = Arrays.stream(LintRule.values())
            .filter(r -> r.source() == LintRule.Source.ENGINE)
            .toList();

        assertThat(registered)
            .as("every ENGINE rule registered, none twice")
            .doesNotHaveDuplicates()
            .containsExactlyInAnyOrderElementsOf(engineRules);
    }

    @Test
    void classifierAdvisoriesAreNotRegisteredAsVisitors() {
        var registered = LintRules.builtIn().stream().map(LintVisitor::rule).toList();
        assertThat(registered)
            .as("classifier advisories are tagged at their emit site, never registered to a visitor")
            .noneMatch(r -> r.source() == LintRule.Source.CLASSIFIER);
    }

    @Test
    void subscribedAndNotLintedKindsPartitionAllNodeKinds() {
        var subscribed = LintRules.subscribedKinds();
        var notLinted = LintRules.NOT_LINTED;

        var overlap = EnumSet.copyOf(subscribed);
        overlap.retainAll(notLinted);
        assertThat(overlap).as("a kind cannot be both subscribed and not-linted").isEmpty();

        var union = EnumSet.copyOf(subscribed);
        union.addAll(notLinted);
        assertThat(union)
            .as("every node kind is subscribed or declared not-linted; no silent skip")
            .containsExactlyInAnyOrder(LintNodeKind.values());
    }

    @Test
    void everyClassifierAdvisoryRuleExists() {
        // The three classifier-owned advisories are tagged onto BuildWarning.LintFinding at their
        // emit sites (TypeBuilder / FieldBuilder); this pins they remain enumerated as CLASSIFIER.
        var classifier = Arrays.stream(LintRule.values())
            .filter(r -> r.source() == LintRule.Source.CLASSIFIER)
            .map(LintRule::id)
            .toList();
        assertThat(classifier).containsExactlyInAnyOrder(
            "splitquery-redundant-on-record-parent",
            "redundant-record-directive",
            "asconnection-same-table-pk-in");
    }

    @Test
    void ruleIdsAreUniqueAndKebabCase() {
        List<String> ids = Arrays.stream(LintRule.values()).map(LintRule::id).toList();
        assertThat(ids).doesNotHaveDuplicates();
        assertThat(ids).allSatisfy(id ->
            assertThat(id).matches("[a-z0-9]+(-[a-z0-9]+)*"));
    }
}
