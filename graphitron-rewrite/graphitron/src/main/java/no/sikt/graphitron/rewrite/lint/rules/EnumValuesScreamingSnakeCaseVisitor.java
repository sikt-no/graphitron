package no.sikt.graphitron.rewrite.lint.rules;

import no.sikt.graphitron.rewrite.lint.LintContext;
import no.sikt.graphitron.rewrite.lint.LintNodeKind;
import no.sikt.graphitron.rewrite.lint.LintRule;
import no.sikt.graphitron.rewrite.lint.LintTarget;
import no.sikt.graphitron.rewrite.lint.LintVisitor;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * {@code enum-values-screaming-snake-case}: enum value names are UPPER_SNAKE_CASE, independent of any
 * {@code @order} / {@code @index} directive on the value. No fix in v1: an enum-value rename can hit
 * SDL default values (see R398).
 */
public final class EnumValuesScreamingSnakeCaseVisitor implements LintVisitor {

    public static final String MESSAGE = "Enum value '%s' should be SCREAMING_SNAKE_CASE.";
    private static final Pattern SCREAMING_SNAKE_CASE = Pattern.compile("[A-Z][A-Z0-9]*(_[A-Z0-9]+)*");

    @Override
    public LintRule rule() {
        return LintRule.ENUM_VALUES_SCREAMING_SNAKE_CASE;
    }

    @Override
    public Set<LintNodeKind> kinds() {
        return Set.of(LintNodeKind.ENUM_VALUE_DEFINITION);
    }

    @Override
    public void inspect(LintTarget target, LintContext ctx) {
        String name = target.name();
        if (name != null && !SCREAMING_SNAKE_CASE.matcher(name).matches()) {
            ctx.report(MESSAGE.formatted(name));
        }
    }
}
