package no.sikt.graphitron.rewrite.lint.rules;

import graphql.language.FieldDefinition;
import no.sikt.graphitron.rewrite.lint.LintContext;
import no.sikt.graphitron.rewrite.lint.LintFix;
import no.sikt.graphitron.rewrite.lint.LintNodeKind;
import no.sikt.graphitron.rewrite.lint.LintRule;
import no.sikt.graphitron.rewrite.lint.LintTarget;
import no.sikt.graphitron.rewrite.lint.LintVisitor;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * {@code field-names-camel-case}: object and interface field names are camelCase. Safe in graphitron
 * because the SDL field name is decoupled from the column via {@code @field(name:)}.
 *
 * <p>Carries a document-local rename fix: a field name is not referenced elsewhere in the SDL, so
 * rewriting the name token is safe. The fix is offered only when the field carries no description,
 * because graphql-java reports a described node's source location at the description rather than the
 * name token, so the exact name range cannot be derived; a described field reports without a fix.
 */
public final class FieldNamesCamelCaseVisitor implements LintVisitor {

    public static final String MESSAGE = "Field name '%s' should be camelCase.";
    public static final String FIX_DESCRIPTION = "Rename field to camelCase";
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
        if (name == null || CAMEL_CASE.matcher(name).matches()) return;
        renameFix(name, target).ifPresentOrElse(
            fix -> ctx.report(MESSAGE.formatted(name), fix),
            () -> ctx.report(MESSAGE.formatted(name)));
    }

    /**
     * A document-local rename fix, present only when the field has no description (so the node's
     * source location is exactly the name token) and the camelCase candidate is a valid, changed
     * name.
     */
    static java.util.Optional<LintFix> renameFix(String name, LintTarget target) {
        if (!(target.node() instanceof FieldDefinition field) || field.getDescription() != null) {
            return java.util.Optional.empty();
        }
        String candidate = toCamelCase(name);
        if (candidate.equals(name) || !CAMEL_CASE.matcher(candidate).matches()) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(
            LintFix.replaceToken(FIX_DESCRIPTION, target.location(), name.length(), candidate));
    }

    /**
     * Best-effort camelCase of an SDL name: split on underscores, lower-case the first segment's
     * initial and upper-case each following segment's initial. A suggestion the user reviews, not a
     * canonical transform.
     */
    static String toCamelCase(String name) {
        String[] parts = name.split("_");
        var sb = new StringBuilder();
        boolean first = true;
        for (String part : parts) {
            if (part.isEmpty()) continue;
            if (first) {
                sb.append(Character.toLowerCase(part.charAt(0))).append(part.substring(1));
                first = false;
            } else {
                sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
            }
        }
        return sb.toString();
    }
}
