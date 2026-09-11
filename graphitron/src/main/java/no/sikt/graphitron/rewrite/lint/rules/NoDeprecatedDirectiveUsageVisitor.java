package no.sikt.graphitron.rewrite.lint.rules;

import no.sikt.graphitron.model.lint.DeprecationRecognizer;
import no.sikt.graphitron.model.lint.LintRule;
import no.sikt.graphitron.rewrite.lint.LintContext;
import no.sikt.graphitron.rewrite.lint.LintNodeKind;
import no.sikt.graphitron.rewrite.lint.LintTarget;
import no.sikt.graphitron.rewrite.lint.LintVisitor;

import java.util.Map;
import java.util.Set;

/**
 * {@code no-deprecated-directive-usage}: a deprecated graphitron directive, directive argument, or
 * directive-argument input field is used in the consumer SDL. Deprecation is what the corpus says
 * it is, through graphitron's unified marker convention via {@link DeprecationRecognizer}; no
 * curated hardcoded list. Excludes {@code @record}, whose deprecation is owned by the
 * redundant-record advisory, so each coordinate is warned exactly once.
 *
 * <p>The finding carries no suggested fix: a quick fix must be registered explicitly, not divined
 * from the deprecation's prose reason (parsing "use @order(index:)" out of a docstring is fragile).
 * The message points the author at the directive's own description for the replacement; an
 * explicitly registered successor fix can follow later without coupling to the comment text.
 *
 * <p>The last of the rules to stop reading a parse tree, and the one that needed capture to catch
 * up first. It asks three things a single node cannot answer, and all three are rows now: whether
 * the corpus deprecates a directive or one of its arguments, what type an argument is declared to
 * be, and which input fields an application named inside a value.
 */
public final class NoDeprecatedDirectiveUsageVisitor implements LintVisitor {

    public static final String DIRECTIVE_MESSAGE = "Directive @%s is deprecated; see its description for the replacement.";
    public static final String ARG_MESSAGE = "Argument '%s' of @%s is deprecated; see its definition for the replacement.";
    public static final String INPUT_FIELD_MESSAGE = "Field '%s' of input '%s' is deprecated; see its definition for the replacement.";

    /** Owned by the redundant-record classifier advisory; excluded here so it is warned once. */
    private static final String RECORD = "record";

    @Override
    public LintRule rule() {
        return LintRule.NO_DEPRECATED_DIRECTIVE_USAGE;
    }

    @Override
    public Set<LintNodeKind> kinds() {
        return Set.of(LintNodeKind.APPLIED_DIRECTIVE);
    }

    @Override
    public void inspect(LintTarget target, LintContext ctx) {
        String name = target.name();
        if (name == null || name.equals(RECORD)) {
            return;
        }

        DeprecationRecognizer deprecation = ctx.deprecation();
        if (deprecation.directiveDeprecation(name).isPresent()) {
            ctx.report(DIRECTIVE_MESSAGE.formatted(name));
            return;
        }

        for (Map.Entry<String, LintTarget.AppliedArgument> passed : target.arguments().entrySet()) {
            String argument = passed.getKey();
            if (deprecation.directiveArgDeprecation(name, argument).isPresent()) {
                ctx.report(ARG_MESSAGE.formatted(argument, name));
                continue;
            }
            String inputType = ctx.namedTypeOfDirectiveArgument(name, argument);
            if (inputType == null) {
                continue;
            }
            // Every field name the value names, at any depth, against the argument's own input
            // type. The flattening is the rule's own: a deprecated field is deprecated wherever it
            // appears, and no nesting this directive vocabulary uses changes which type owns one.
            for (String field : passed.getValue().namedFields()) {
                if (deprecation.inputFieldDeprecation(inputType, field).isPresent()) {
                    ctx.report(INPUT_FIELD_MESSAGE.formatted(field, inputType));
                }
            }
        }
    }
}
