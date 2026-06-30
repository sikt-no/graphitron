package no.sikt.graphitron.rewrite.lint.rules;

import graphql.language.Directive;
import graphql.language.StringValue;
import no.sikt.graphitron.rewrite.lint.LintContext;
import no.sikt.graphitron.rewrite.lint.LintNodeKind;
import no.sikt.graphitron.rewrite.lint.LintRule;
import no.sikt.graphitron.rewrite.lint.LintTarget;
import no.sikt.graphitron.rewrite.lint.LintVisitor;

import java.util.Set;

/**
 * {@code deprecations-have-a-reason}: every native {@code @deprecated} carries a non-empty
 * {@code reason}. Additive fix (insert a {@code reason: "..."} placeholder) lands with the fix slice.
 */
public final class DeprecationsHaveAReasonVisitor implements LintVisitor {

    public static final String MESSAGE = "@deprecated should carry a non-empty 'reason'.";
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
        if (!hasReason) {
            ctx.report(MESSAGE);
        }
    }
}
