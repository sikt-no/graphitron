package no.sikt.graphitron.rewrite.lint.rules;

import graphql.language.Argument;
import graphql.language.ArrayValue;
import graphql.language.DirectiveDefinition;
import graphql.language.InputValueDefinition;
import graphql.language.ListType;
import graphql.language.NonNullType;
import graphql.language.ObjectField;
import graphql.language.ObjectValue;
import graphql.language.Type;
import graphql.language.TypeName;
import graphql.language.Value;
import no.sikt.graphitron.rewrite.lint.DeprecationRecognizer;
import no.sikt.graphitron.rewrite.lint.LintContext;
import no.sikt.graphitron.rewrite.lint.LintNodeKind;
import no.sikt.graphitron.rewrite.lint.LintRule;
import no.sikt.graphitron.rewrite.lint.LintTarget;
import no.sikt.graphitron.rewrite.lint.LintVisitor;

import java.util.Set;

/**
 * {@code no-deprecated-directive-usage}: a deprecated graphitron directive, directive argument, or
 * directive-argument input field is used in the consumer SDL. Deprecation is recognised through
 * graphitron's unified {@code @deprecated}-marker convention via {@link DeprecationRecognizer}
 * (extracted build-side for exactly this rule); no curated hardcoded list. Excludes
 * {@code @record}, whose deprecation is owned by the redundant-record advisory, so each coordinate is
 * warned exactly once.
 *
 * <p>The finding carries no suggested fix: a quick fix must be registered explicitly, not divined
 * from the deprecation's prose reason (parsing "use @order(index:)" out of a docstring is fragile).
 * The message points the author at the directive's own description for the replacement; an explicitly
 * registered successor fix can follow later without coupling to the comment text.
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
        if (!(target.node() instanceof graphql.language.Directive directive)) return;
        String name = directive.getName();
        if (name.equals(RECORD)) return;

        DeprecationRecognizer recognizer = ctx.deprecation();
        if (recognizer.directiveDeprecation(name).isPresent()) {
            ctx.report(DIRECTIVE_MESSAGE.formatted(name));
            return;
        }

        DirectiveDefinition definition = ctx.registry().getDirectiveDefinition(name).orElse(null);
        for (Argument arg : directive.getArguments()) {
            if (recognizer.directiveArgDeprecation(name, arg.getName()).isPresent()) {
                ctx.report(ARG_MESSAGE.formatted(arg.getName(), name));
                continue;
            }
            String inputType = definition == null ? null : inputTypeOfArg(definition, arg.getName());
            if (inputType != null) {
                checkDeprecatedInputFields(arg.getValue(), inputType, recognizer, ctx);
            }
        }
    }

    private static void checkDeprecatedInputFields(
        Value<?> value, String inputType, DeprecationRecognizer recognizer, LintContext ctx
    ) {
        switch (value) {
            case ObjectValue object -> {
                for (ObjectField field : object.getObjectFields()) {
                    if (recognizer.inputFieldDeprecation(inputType, field.getName()).isPresent()) {
                        ctx.report(INPUT_FIELD_MESSAGE.formatted(field.getName(), inputType));
                    }
                }
            }
            case ArrayValue array -> {
                for (Value<?> element : array.getValues()) {
                    checkDeprecatedInputFields(element, inputType, recognizer, ctx);
                }
            }
            default -> { /* scalar / enum / variable values carry no input-field coordinates */ }
        }
    }

    private static String inputTypeOfArg(DirectiveDefinition definition, String argName) {
        for (InputValueDefinition arg : definition.getInputValueDefinitions()) {
            if (arg.getName().equals(argName)) {
                return unwrap(arg.getType());
            }
        }
        return null;
    }

    private static String unwrap(Type<?> type) {
        return switch (type) {
            case NonNullType nn -> unwrap(nn.getType());
            case ListType list -> unwrap(list.getType());
            case TypeName named -> named.getName();
            default -> null;
        };
    }
}
