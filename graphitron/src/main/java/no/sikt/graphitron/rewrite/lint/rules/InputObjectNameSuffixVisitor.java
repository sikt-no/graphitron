package no.sikt.graphitron.rewrite.lint.rules;

import no.sikt.graphitron.rewrite.lint.LintContext;
import no.sikt.graphitron.rewrite.lint.LintNodeKind;
import no.sikt.graphitron.rewrite.lint.LintRule;
import no.sikt.graphitron.rewrite.lint.LintTarget;
import no.sikt.graphitron.rewrite.lint.LintVisitor;

import java.util.Set;

/**
 * {@code input-object-name-suffix}: input object type names end in {@code Input}. Opinionated, but v1
 * has no off switch so it ships on; a future config can make it opt-out. No fix in v1 (a type rename
 * ripples to references).
 */
public final class InputObjectNameSuffixVisitor implements LintVisitor {

    public static final String MESSAGE = "Input object type '%s' should have an 'Input' suffix.";
    private static final String SUFFIX = "Input";

    @Override
    public LintRule rule() {
        return LintRule.INPUT_OBJECT_NAME_SUFFIX;
    }

    @Override
    public Set<LintNodeKind> kinds() {
        return Set.of(LintNodeKind.INPUT_OBJECT_TYPE);
    }

    @Override
    public void inspect(LintTarget target, LintContext ctx) {
        String name = target.name();
        if (name != null && !name.endsWith(SUFFIX)) {
            ctx.report(MESSAGE.formatted(name));
        }
    }
}
