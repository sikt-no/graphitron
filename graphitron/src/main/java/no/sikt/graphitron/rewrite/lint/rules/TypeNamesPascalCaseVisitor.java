package no.sikt.graphitron.rewrite.lint.rules;

import no.sikt.graphitron.rewrite.lint.LintContext;
import no.sikt.graphitron.rewrite.lint.LintNodeKind;
import no.sikt.graphitron.rewrite.lint.LintRule;
import no.sikt.graphitron.rewrite.lint.LintTarget;
import no.sikt.graphitron.rewrite.lint.LintVisitor;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * {@code type-names-pascal-case}: object, interface, union, enum, input, and scalar type names are
 * PascalCase. No fix in v1: a type rename touches every SDL reference (see R398).
 */
public final class TypeNamesPascalCaseVisitor implements LintVisitor {

    public static final String MESSAGE = "Type name '%s' should be PascalCase.";
    private static final Pattern PASCAL_CASE = Pattern.compile("[A-Z][A-Za-z0-9]*");

    @Override
    public LintRule rule() {
        return LintRule.TYPE_NAMES_PASCAL_CASE;
    }

    @Override
    public Set<LintNodeKind> kinds() {
        return Set.of(
            LintNodeKind.OBJECT_TYPE, LintNodeKind.INTERFACE_TYPE, LintNodeKind.UNION_TYPE,
            LintNodeKind.ENUM_TYPE, LintNodeKind.INPUT_OBJECT_TYPE, LintNodeKind.SCALAR_TYPE);
    }

    @Override
    public void inspect(LintTarget target, LintContext ctx) {
        String name = target.name();
        if (name != null && !PASCAL_CASE.matcher(name).matches()) {
            ctx.report(MESSAGE.formatted(name));
        }
    }
}
