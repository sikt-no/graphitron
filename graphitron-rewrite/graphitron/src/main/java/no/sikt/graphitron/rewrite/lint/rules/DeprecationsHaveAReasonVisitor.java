package no.sikt.graphitron.rewrite.lint.rules;

import graphql.language.Directive;
import graphql.language.SourceLocation;
import graphql.language.StringValue;
import no.sikt.graphitron.rewrite.lint.LintContext;
import no.sikt.graphitron.rewrite.lint.LintFix;
import no.sikt.graphitron.rewrite.lint.LintNodeKind;
import no.sikt.graphitron.rewrite.lint.LintRule;
import no.sikt.graphitron.rewrite.lint.LintTarget;
import no.sikt.graphitron.rewrite.lint.LintVisitor;

import java.util.Set;

/**
 * {@code deprecations-have-a-reason}: every native {@code @deprecated} carries a non-empty
 * {@code reason}.
 *
 * <p>Carries an additive fix: insert a {@code (reason: "...")} placeholder immediately after the
 * {@code @deprecated} name. Additive edits change nothing existing, so the fix is always safe. It is
 * offered only for the bare {@code @deprecated} form (no arguments), the reason-less trigger; the
 * pathological empty-parens form is left without a fix.
 */
public final class DeprecationsHaveAReasonVisitor implements LintVisitor {

    public static final String MESSAGE = "@deprecated should carry a non-empty 'reason'.";
    public static final String FIX_DESCRIPTION = "Add a reason placeholder";
    public static final String REASON_PLACEHOLDER = "(reason: \"TODO: describe the deprecation\")";
    private static final String DEPRECATED = "deprecated";
    private static final String REASON = "reason";

    @Override
    public LintRule rule() {
        return LintRule.DEPRECATIONS_HAVE_A_REASON;
    }

    @Override
    public Set<LintNodeKind> kinds() {
        return Set.of(LintNodeKind.APPLIED_DIRECTIVE);
    }

    @Override
    public void inspect(LintTarget target, LintContext ctx) {
        if (!(target.node() instanceof Directive directive) || !directive.getName().equals(DEPRECATED)) {
            return;
        }
        var reason = directive.getArgument(REASON);
        boolean hasReason = reason != null
            && reason.getValue() instanceof StringValue s
            && !s.getValue().isBlank();
        if (hasReason) return;

        if (directive.getArguments().isEmpty()) {
            // Insert right after "@deprecated": the '@' is at the directive's column, the name is the
            // ten following characters, so the insertion point is column + 1 + "deprecated".length().
            SourceLocation at = new SourceLocation(
                directive.getSourceLocation().getLine(),
                directive.getSourceLocation().getColumn() + 1 + DEPRECATED.length());
            ctx.report(MESSAGE, LintFix.insertAt(FIX_DESCRIPTION, at, REASON_PLACEHOLDER));
        } else {
            ctx.report(MESSAGE);
        }
    }
}
