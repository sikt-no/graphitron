package no.sikt.graphitron.rewrite.lint.rules;

import no.sikt.graphitron.rewrite.lint.LintContext;
import no.sikt.graphitron.rewrite.lint.LintNodeKind;
import no.sikt.graphitron.rewrite.lint.LintRule;
import no.sikt.graphitron.rewrite.lint.LintTarget;
import no.sikt.graphitron.rewrite.lint.LintVisitor;

import java.util.Set;

/**
 * {@code no-typename-prefix}: an object or interface field name must not be prefixed with its
 * enclosing type's name (for example {@code User.userName} should be {@code User.name}). Purely
 * syntactic and document-local; a rename fix lands with the fix slice (see R398).
 */
public final class NoTypenamePrefixVisitor implements LintVisitor {

    public static final String MESSAGE = "Field '%s.%s' is prefixed with its type name; drop the prefix.";

    @Override
    public LintRule rule() {
        return LintRule.NO_TYPENAME_PREFIX;
    }

    @Override
    public Set<LintNodeKind> kinds() {
        return Set.of(LintNodeKind.FIELD_DEFINITION);
    }

    @Override
    public void inspect(LintTarget target, LintContext ctx) {
        String type = target.enclosingTypeName();
        String field = target.name();
        if (type == null || field == null) return;
        if (field.length() > type.length()
            && field.regionMatches(true, 0, type, 0, type.length())
            && Character.isUpperCase(field.charAt(type.length()))) {
            ctx.report(MESSAGE.formatted(type, field));
        }
    }
}
