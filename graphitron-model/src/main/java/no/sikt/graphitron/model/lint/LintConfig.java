package no.sikt.graphitron.model.lint;

import java.util.List;
import java.util.Set;

/**
 * Consumer lint-suppression configuration: rule ids to silence everywhere and type-name glob
 * patterns to exclude from the SDL lint engine's walk. Built once from the Maven {@code <lint>} block
 * and threaded through {@link no.sikt.graphitron.model.config.RunContext} so suppression is applied at
 * the one build evaluator; because the LSP replays that {@code ValidationReport} and the MCP
 * {@code diagnostics} tool projects it, a suppressed finding never surfaces in CI, the editor
 * squiggle, or the MCP tool, from one definition with no second filter.
 *
 * <p>The two axes have deliberately different scope:
 * <ul>
 *   <li>{@code disabledRuleIds} drops any {@link no.sikt.graphitron.model.diagnostics.BuildWarning.LintFinding}
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
     * Checks every disabled rule id against what may be disabled: a lint rule's id or a
     * warning-severity defect's code, the codes being the store's ({@code graphitron_defect_type}),
     * so this runs once the store is open. An id naming neither is a typo the build must not silently
     * ignore, and the failure lists every id it accepts, so a retired id meets its successor there;
     * an error's code is refused on its own, an error never being silenced (Alf, 2026-11-19).
     */
    public void check(Set<String> warningCodes, Set<String> errorCodes) {
        var errors = disabledRuleIds.stream().filter(errorCodes::contains).sorted().toList();
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("<disabledRules> names error code(s) "
                + String.join(", ", errors) + ", which cannot be disabled: an error stops the build"
                + " whatever the configuration says.");
        }
        var lintIds = LintRule.ids();
        var unknown = disabledRuleIds.stream()
            .filter(id -> !lintIds.contains(id) && !warningCodes.contains(id))
            .sorted()
            .toList();
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException(
                "Unknown lint rule id(s) in <disabledRules>: " + String.join(", ", unknown)
                    + ". Valid rule ids are the lint rules " + String.join(", ", lintIds)
                    + " and the warning codes " + String.join(", ", warningCodes.stream().sorted().toList())
                    + ".");
        }
    }
}
