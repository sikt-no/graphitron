package no.sikt.graphitron.rewrite.lint.rules;

import no.sikt.graphitron.rewrite.lint.LintContext;
import no.sikt.graphitron.rewrite.lint.LintNodeKind;
import no.sikt.graphitron.rewrite.lint.LintRule;
import no.sikt.graphitron.rewrite.lint.LintTarget;
import no.sikt.graphitron.rewrite.lint.LintVisitor;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * {@code field-names-camel-case}: object and interface field names are camelCase. Safe in graphitron
 * because the SDL field name is decoupled from the column via {@code @field(name:)}.
 */
public final class FieldNamesCamelCaseVisitor implements LintVisitor {

    public static final String MESSAGE = "Field name '%s' should be camelCase.";
    static final Pattern CAMEL_CASE = Pattern.compile("[a-z][A-Za-z0-9]*");

    @Override
    public LintRule rule() {
        return LintRule.FIELD_NAMES_CAMEL_CASE;
    }

    @Override
    public Set<LintNodeKind> kinds() {
        return Set.of(LintNodeKind.FIELD_DEFINITION);
    }

    @Override
    public void inspect(LintTarget target, LintContext ctx) {
        String name = target.name();
        if (name != null && !CAMEL_CASE.matcher(name).matches()) {
            ctx.report(MESSAGE.formatted(name));
        }
    }
}
