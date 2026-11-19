package no.sikt.graphitron.rewrite.lint;

import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import no.sikt.graphitron.model.lint.LintConfig;

/**
 * Config-identity validation for {@link LintConfig}. A disabled id names a lint rule or a
 * warning-severity defect's code, so a typo is a build error rather than a silently-ignored line,
 * and an error's code is refused. Asserts the failure and that the message carries the valid
 * namespace, not the exact wording (per the design principles' code-string-assertion discipline).
 */
@UnitTier
class LintConfigTest {

    private static final Set<String> WARNINGS = Set.of("NODE_METADATA_UNREADABLE");
    private static final Set<String> ERRORS = Set.of("COLUMN_UNRESOLVED");

    @Test
    void check_unknownRuleId_failsAndListsLintIdsAndWarningCodes() {
        var config = new LintConfig(Set.of("no-such-rule"), List.of());
        assertThatThrownBy(() -> config.check(WARNINGS, ERRORS))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("no-such-rule")
            // the message lists the valid namespace so a typo is self-correcting
            .hasMessageContaining("input-object-name-suffix")
            .hasMessageContaining("NODE_METADATA_UNREADABLE");
    }

    @Test
    void check_errorCode_isRefused() {
        var config = new LintConfig(Set.of("COLUMN_UNRESOLVED"), List.of());
        assertThatThrownBy(() -> config.check(WARNINGS, ERRORS))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("COLUMN_UNRESOLVED")
            .hasMessageNotContaining("Unknown");
    }

    @Test
    void check_lintIdAndWarningCode_pass() {
        var config = new LintConfig(
            Set.of("input-object-name-suffix", "NODE_METADATA_UNREADABLE"), List.of("Legacy*"));
        assertThatCode(() -> config.check(WARNINGS, ERRORS)).doesNotThrowAnyException();
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
    void check_excludedTypePatternsAreNotCheckedAgainstRuleIds() {
        // Only disabledRuleIds names a rule; an arbitrary type glob is a legal exclusion.
        var config = new LintConfig(Set.of(), List.of("Anything*", "Foo?"));
        assertThatCode(() -> config.check(WARNINGS, ERRORS)).doesNotThrowAnyException();
    }
}
