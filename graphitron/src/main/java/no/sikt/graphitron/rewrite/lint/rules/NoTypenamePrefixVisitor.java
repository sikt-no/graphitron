package no.sikt.graphitron.rewrite.lint.rules;

import graphql.language.FieldDefinition;
import no.sikt.graphitron.rewrite.lint.LintContext;
import no.sikt.graphitron.rewrite.lint.LintFix;
import no.sikt.graphitron.rewrite.lint.LintNodeKind;
import no.sikt.graphitron.rewrite.lint.LintRule;
import no.sikt.graphitron.rewrite.lint.LintTarget;
import no.sikt.graphitron.rewrite.lint.LintVisitor;

import java.util.Optional;
import java.util.Set;

/**
 * {@code no-typename-prefix}: an object or interface field name must not be prefixed with its
 * enclosing type's name (for example {@code User.userName} should be {@code User.name}). Purely
 * syntactic and document-local.
 *
 * <p>Carries a document-local rename fix (drop the prefix, lower-case the remainder), offered only
 * when the field carries no description, because graphql-java reports a described node's source
 * location at the description rather than the name token; a described field reports without a fix.
 */
public final class NoTypenamePrefixVisitor implements LintVisitor {

    public static final String MESSAGE = "Field '%s.%s' is prefixed with its type name; drop the prefix.";
    public static final String FIX_DESCRIPTION = "Drop the type-name prefix";

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
            renameFix(type, field, target).ifPresentOrElse(
                fix -> ctx.report(MESSAGE.formatted(type, field), fix),
                () -> ctx.report(MESSAGE.formatted(type, field)));
        }
    }

    private static Optional<LintFix> renameFix(String type, String field, LintTarget target) {
        if (!(target.node() instanceof FieldDefinition def) || def.getDescription() != null) {
            return Optional.empty();
        }
        String remainder = field.substring(type.length());
        String candidate = Character.toLowerCase(remainder.charAt(0)) + remainder.substring(1);
        return Optional.of(
            LintFix.replaceToken(FIX_DESCRIPTION, target.location(), field.length(), candidate));
    }
}
