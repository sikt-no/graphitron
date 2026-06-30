package no.sikt.graphitron.rewrite;

import graphql.language.SourceLocation;
import no.sikt.graphitron.rewrite.lint.LintFix;
import no.sikt.graphitron.rewrite.lint.LintRule;

import java.util.Optional;

/**
 * A non-fatal advisory produced during the build. Surfaced by {@code ValidateMojo} /
 * {@code GenerateMojo} via {@code getLog().warn(...)}, replayed into LSP squiggles by
 * {@code Diagnostics.validatorDiagnostics}, and projected onto the MCP {@code diagnostics} tool.
 * A warning never fails the build; it flags schema shapes accepted for compatibility but discouraged.
 *
 * <p>Sealed into two arms so a finding's rule is a type and its fix lives only on the arm where it
 * is meaningful, rather than behind a nullable field (R398):
 *
 * <ul>
 *   <li>{@link NoRule}: an untagged advisory not attributable to a lint rule (for example the
 *       federation compound-key advisory {@code EntityResolutionBuilder} emits). Shape-parallel to
 *       the pre-R398 flat record.</li>
 *   <li>{@link LintFinding}: a lint-rule finding carrying a typed {@link LintRule} and an
 *       {@link Optional} {@link LintFix}. The classifier-owned advisories (redundant {@code @record},
 *       {@code @splitQuery}-on-record, same-table {@code @asConnection}) are this arm too, tagged at
 *       their existing classifier emit site; they are not re-derived by the lint engine.</li>
 * </ul>
 *
 * <p>Both arms flow into {@link ValidationReport} exactly as warnings did before sealing, so the LSP
 * replay and the MCP projection need no new transport. {@code location.getSourceName()} carries the
 * source file path when the schema was parsed via {@code RewriteSchemaLoader}.
 */
public sealed interface BuildWarning permits BuildWarning.NoRule, BuildWarning.LintFinding {

    String message();

    SourceLocation location();

    /** An advisory not attributable to a lint rule. */
    record NoRule(String message, SourceLocation location) implements BuildWarning {}

    /** A lint-rule finding: a typed {@link LintRule} plus an optional, user-accepted {@link LintFix}. */
    record LintFinding(String message, SourceLocation location, LintRule rule, Optional<LintFix> fix)
        implements BuildWarning {

        public LintFinding {
            fix = fix == null ? Optional.empty() : fix;
        }

        /** Convenience for a fix-less finding. */
        public static LintFinding of(String message, SourceLocation location, LintRule rule) {
            return new LintFinding(message, location, rule, Optional.empty());
        }

        /** Convenience for a fix-bearing finding. */
        public static LintFinding of(String message, SourceLocation location, LintRule rule, LintFix fix) {
            return new LintFinding(message, location, rule, Optional.of(fix));
        }
    }
}
