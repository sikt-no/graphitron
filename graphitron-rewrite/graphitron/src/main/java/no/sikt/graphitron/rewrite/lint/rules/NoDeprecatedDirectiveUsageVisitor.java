package no.sikt.graphitron.rewrite.lint.rules;

import graphql.language.Argument;
import graphql.language.ArrayValue;
import graphql.language.Directive;
import graphql.language.DirectiveDefinition;
import graphql.language.InputValueDefinition;
import graphql.language.ListType;
import graphql.language.NonNullType;
import graphql.language.ObjectField;
import graphql.language.ObjectValue;
import graphql.language.SourceLocation;
import graphql.language.Type;
import graphql.language.TypeName;
import graphql.language.Value;
import no.sikt.graphitron.rewrite.lint.DeprecationRecognizer;
import no.sikt.graphitron.rewrite.lint.LintContext;
import no.sikt.graphitron.rewrite.lint.LintFix;
import no.sikt.graphitron.rewrite.lint.LintNodeKind;
import no.sikt.graphitron.rewrite.lint.LintRule;
import no.sikt.graphitron.rewrite.lint.LintTarget;
import no.sikt.graphitron.rewrite.lint.LintVisitor;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code no-deprecated-directive-usage}: a deprecated graphitron directive, directive argument, or
 * directive-argument input field is used in the consumer SDL. Deprecation is recognised through
 * graphitron's unified {@code @deprecated}-marker convention via {@link DeprecationRecognizer}
 * (extracted build-side for exactly this rule, R398); no curated hardcoded list. Excludes
 * {@code @record}, whose deprecation is owned by the redundant-record advisory, so each coordinate is
 * warned exactly once. Subsumes the retired R296.
 */
public final class NoDeprecatedDirectiveUsageVisitor implements LintVisitor {

    public static final String DIRECTIVE_MESSAGE = "Directive @%s is deprecated; see its description for the replacement.";
    public static final String ARG_MESSAGE = "Argument '%s' of @%s is deprecated; see its definition for the replacement.";
    public static final String INPUT_FIELD_MESSAGE = "Field '%s' of input '%s' is deprecated; see its definition for the replacement.";
    public static final String FIX_DESCRIPTION = "Replace with the successor directive";

    /** Owned by the redundant-record classifier advisory; excluded here so it is warned once. */
    private static final String RECORD = "record";

    /**
     * The successor a whole-directive docstring deprecation points at, in graphitron's established
     * {@code @deprecated use @order(index:) instead} convention (directives.graphqls:243). The
     * directive name is required; the parenthesised argument name is optional and used to also rename
     * the sole applied argument, so the swap yields valid SDL rather than a directive rename that
     * leaves a now-wrong argument name. No hardcoded per-directive list: the replacement is derived
     * from the same docstring the recogniser reads.
     */
    private static final Pattern SUCCESSOR = Pattern.compile("use\\s+@(\\w+)(?:\\((\\w+):)?");

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
        var directiveDeprecation = recognizer.directiveDeprecation(name);
        if (directiveDeprecation.isPresent()) {
            successorFix(directive, directiveDeprecation.get().reason()).ifPresentOrElse(
                fix -> ctx.report(DIRECTIVE_MESSAGE.formatted(name), fix),
                () -> ctx.report(DIRECTIVE_MESSAGE.formatted(name)));
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

    /**
     * A local-replacement fix for a whole-directive deprecation whose docstring names a successor
     * (e.g. {@code @index} to {@code @order}). The directive-name token is rewritten in place (the
     * name starts one column after the directive's {@code @}); when the docstring also names the
     * successor's argument and the applied directive has exactly one argument, that argument is
     * renamed too, so {@code @index(name: "x")} becomes valid {@code @order(index: "x")} rather than
     * a directive rename that leaves a wrong argument name. Absent a recognised successor, no fix.
     */
    private static Optional<LintFix> successorFix(Directive directive, String reason) {
        if (reason == null) return Optional.empty();
        Matcher m = SUCCESSOR.matcher(reason);
        if (!m.find()) return Optional.empty();
        String newDirective = m.group(1);
        String oldDirective = directive.getName();
        if (newDirective.equals(oldDirective)) return Optional.empty();

        var edits = new ArrayList<LintFix.Edit>();
        SourceLocation at = directive.getSourceLocation();
        // The name token starts one column past the '@'.
        SourceLocation nameStart = new SourceLocation(at.getLine(), at.getColumn() + 1);
        SourceLocation nameEnd = new SourceLocation(at.getLine(), at.getColumn() + 1 + oldDirective.length());
        edits.add(new LintFix.Edit(nameStart, nameEnd, newDirective));

        String newArg = m.group(2);
        List<Argument> args = directive.getArguments();
        if (newArg != null && args.size() == 1 && !args.get(0).getName().equals(newArg)) {
            Argument arg = args.get(0);
            SourceLocation argAt = arg.getSourceLocation();
            SourceLocation argEnd = new SourceLocation(argAt.getLine(), argAt.getColumn() + arg.getName().length());
            edits.add(new LintFix.Edit(argAt, argEnd, newArg));
        }
        return Optional.of(new LintFix(FIX_DESCRIPTION, edits));
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
