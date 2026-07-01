package no.sikt.graphitron.rewrite.lint;

import java.util.List;
import java.util.Set;

/**
 * Consumer lint-suppression configuration (R408): rule ids to silence everywhere and type-name glob
 * patterns to exclude from the SDL lint engine's walk. Built once from the Maven {@code <lint>} block
 * and threaded through {@link no.sikt.graphitron.rewrite.RewriteContext} so suppression is applied at
 * the one build evaluator; because the LSP replays that {@code ValidationReport} and the MCP
 * {@code diagnostics} tool projects it, a suppressed finding never surfaces in CI, the editor
 * squiggle, or the MCP tool, from one definition with no second filter.
 *
 * <p>The two axes have deliberately different scope (see R408):
 * <ul>
 *   <li>{@code disabledRuleIds} drops any {@link no.sikt.graphitron.rewrite.BuildWarning.LintFinding}
 *       carrying that rule id from the combined build-warning list, so it covers both engine findings
 *       and the classifier-sourced advisories.</li>
 *   <li>{@code excludedTypePatterns} skips the engine's AST walk for matching type names only; the
 *       classifier advisories never pass through that walk (they arrive pre-formed on the schema's
 *       warning list), and the flat warning list carries no structured owning-type handle to glob
 *       against, so a classifier advisory on an excluded type still fires and is suppressible by rule
 *       id alone.</li>
 * </ul>
 */
public record LintConfig(Set<String> disabledRuleIds, List<String> excludedTypePatterns) {

    private static final LintConfig EMPTY = new LintConfig(Set.of(), List.of());

    public LintConfig {
        disabledRuleIds = Set.copyOf(disabledRuleIds);
        excludedTypePatterns = List.copyOf(excludedTypePatterns);
    }

    /** The no-suppression config: every rule fires on every author-owned type. */
    public static LintConfig empty() {
        return EMPTY;
    }

    public boolean isEmpty() {
        return disabledRuleIds.isEmpty() && excludedTypePatterns.isEmpty();
    }

    /**
     * Builds a config, validating every disabled rule id against {@link LintRule#ids()}. Config
     * identity is typed against the rule enum: an id that resolves to no rule is a typo the build must
     * not silently ignore, so this throws {@link IllegalArgumentException} naming the offending id(s)
     * and listing the valid ones. The Maven seam turns that into a build failure.
     */
    public static LintConfig validated(Set<String> disabledRuleIds, List<String> excludedTypePatterns) {
        var known = LintRule.ids();
        var unknown = disabledRuleIds.stream()
            .filter(id -> !known.contains(id))
            .sorted()
            .toList();
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException(
                "Unknown lint rule id(s) in <disabledRules>: " + String.join(", ", unknown)
                    + ". Valid rule ids are: " + String.join(", ", known) + ".");
        }
        return new LintConfig(disabledRuleIds, excludedTypePatterns);
    }
}
