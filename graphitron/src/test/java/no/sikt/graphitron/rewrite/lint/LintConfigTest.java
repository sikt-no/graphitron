package no.sikt.graphitron.rewrite.lint;

import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Config-identity validation for {@link LintConfig}. A configured rule id is typed against the
 * rule enum, so a typo is a build error rather than a silently-ignored line. Asserts the failure and
 * that the message carries the valid namespace, not the exact wording (per the design principles'
 * code-string-assertion discipline).
 */
@UnitTier
class LintConfigTest {

    @Test
    void validated_unknownRuleId_failsAndListsValidIds() {
        assertThatThrownBy(() -> LintConfig.validated(Set.of("no-such-rule"), List.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("no-such-rule")
            // the message lists the valid namespace so a typo is self-correcting
            .hasMessageContaining("input-object-name-suffix");
    }

    @Test
    void validated_knownRuleIds_buildsConfigCarryingBothAxes() {
        var config = LintConfig.validated(
            Set.of("input-object-name-suffix"), List.of("Legacy*"));

        assertThat(config.disabledRuleIds()).containsExactly("input-object-name-suffix");
        assertThat(config.excludedTypePatterns()).containsExactly("Legacy*");
        assertThat(config.isEmpty()).isFalse();
    }

    @Test
    void empty_suppressesNothing() {
        assertThat(LintConfig.empty().isEmpty()).isTrue();
        assertThat(LintConfig.empty().disabledRuleIds()).isEmpty();
        assertThat(LintConfig.empty().excludedTypePatterns()).isEmpty();
    }

    @Test
    void validated_excludedTypePatternsAreNotValidatedAgainstRuleIds() {
        // Only disabledRuleIds names the rule enum; an arbitrary type glob is a legal exclusion.
        var config = LintConfig.validated(Set.of(), List.of("Anything*", "Foo?"));
        assertThat(config.excludedTypePatterns()).containsExactly("Anything*", "Foo?");
    }
}
