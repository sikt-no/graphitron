package no.sikt.graphitron.rewrite.lint.rules;

import no.sikt.graphitron.rewrite.lint.LintContext;
import no.sikt.graphitron.rewrite.lint.LintNodeKind;
import no.sikt.graphitron.rewrite.lint.LintRule;
import no.sikt.graphitron.rewrite.lint.LintTarget;
import no.sikt.graphitron.rewrite.lint.LintVisitor;

import java.util.Set;

/**
 * {@code input-and-argument-names-camel-case}: input-object field names and field-argument names are
 * camelCase. No fix in v1: a renamed argument can still be referenced by a default value or a
 * directive-arg coordinate, so it stays no-fix until that confirmation lands.
 */
public final class InputAndArgumentNamesCamelCaseVisitor implements LintVisitor {

    public static final String MESSAGE = "Name '%s' should be camelCase.";

    @Override
    public LintRule rule() {
        return LintRule.INPUT_AND_ARGUMENT_NAMES_CAMEL_CASE;
    }

    @Override
    public Set<LintNodeKind> kinds() {
        return Set.of(LintNodeKind.INPUT_FIELD_DEFINITION, LintNodeKind.ARGUMENT_DEFINITION);
    }

    @Override
    public void inspect(LintTarget target, LintContext ctx) {
        String name = target.name();
        if (name != null && !FieldNamesCamelCaseVisitor.CAMEL_CASE.matcher(name).matches()) {
            ctx.report(MESSAGE.formatted(name));
        }
    }
}
