package no.sikt.graphitron.rewrite.walker;

import graphql.schema.GraphQLSchema;
import no.sikt.graphitron.rewrite.model.ErrorChannel;
import no.sikt.graphitron.rewrite.model.ErrorChannelWalkerError;
import no.sikt.graphitron.rewrite.model.GraphitronType.ErrorType;
import no.sikt.graphitron.rewrite.model.OutcomeType;
import no.sikt.graphitron.rewrite.model.Rejection;
import no.sikt.graphitron.rewrite.model.WalkerResult;
import no.sikt.graphitron.rewrite.walker.internal.ChannelRuleChecks;
import no.sikt.graphitron.rewrite.walker.internal.HandlerAccessorCheck;

import java.util.ArrayList;
import java.util.List;

/**
 * R244 walker: resolves the {@link ErrorChannel.Mapped} carrier for an in-scope ({@code @service}
 * / {@code @tableMethod}) outcome field from its classified {@link OutcomeType}. The output-walking
 * analogue of R238's {@code ServiceMethodCallWalker}: a thin producer over an explicit substrate,
 * different SDL surface (the outcome type and its errors field rather than the field's arguments).
 *
 * <h3>Stages</h3>
 *
 * <ol>
 *   <li><b>Identify mapped {@code @error} types.</b> Read directly off
 *       {@link OutcomeType#errorsField()}; the {@link no.sikt.graphitron.rewrite.model.ChildField.ErrorsField}
 *       already carries the flattened list (single {@code @error}, union members, or interface
 *       implementations) in source order, so the walker does not re-scan.</li>
 *   <li><b>Run channel-level rules.</b> Rule 7 (multi-VALIDATION) and rule 8 (duplicate
 *       match-criteria) via {@link ChannelRuleChecks}; each firing produces a
 *       {@link ErrorChannelWalkerError.ChannelRuleViolation} with the rule number and detail.</li>
 *   <li><b>Reflect on handler source-classes for accessor coverage</b> via
 *       {@link HandlerAccessorCheck}; each miss produces a
 *       {@link ErrorChannelWalkerError.HandlerSourceAccessorMissing}.</li>
 *   <li><b>Resolve the mappings-constant name</b> as {@code SCREAMING_SNAKE(outcomeTypeName)}; the
 *       build-scoped {@code MappingsConstantNameDedup} pass applies any collision suffix later,
 *       exactly as it does for the bare per-field name on the legacy path.</li>
 * </ol>
 *
 * <p>Errors collect across stages rather than short-circuiting. {@link WalkerResult.Ok} carries the
 * {@link ErrorChannel.Mapped}; {@link WalkerResult.Err} carries the typed
 * {@link ErrorChannelWalkerError} arms, signalling the orchestrator to drop the field from the
 * classified set (no fallback to {@code UnclassifiedField}).
 *
 * <h3>Substrate (In Progress revision of the spec signature)</h3>
 *
 * The spec sketched {@code walk(OutcomeType, ClassLoader, MappingsConstantNameDedup)}. Two
 * adjustments fell out at implementation time: the accessor-coverage stage needs the
 * {@link GraphQLSchema} (to read each {@code @error} type's declared SDL fields) and a
 * {@link ReflectTypeResolver} (to map those field types onto the reflect types the accessor check
 * resolves against), so both are parameters; and {@code MappingsConstantNameDedup} is a
 * post-classification cross-field pass with no per-field entry point, so the walker stamps the bare
 * name and the existing dedup pass rewrites collisions downstream, matching how the legacy
 * per-field classifier already works.
 */
public final class ErrorChannelWalker {

    public WalkerResult<ErrorChannel.Mapped> walk(
            OutcomeType outcomeType,
            GraphQLSchema schema,
            ClassLoader codegenLoader,
            ReflectTypeResolver reflectTypeResolver) {
        List<ErrorType> mapped = outcomeType.errorsField().errorTypes();
        String outcomeTypeName = outcomeType.errorsField().parentTypeName();
        String errorsFieldName = outcomeType.errorsField().name();

        List<Rejection.AuthorError> errors = new ArrayList<>();

        String rule7 = ChannelRuleChecks.checkMultiValidation(mapped);
        if (rule7 != null) {
            errors.add(new ErrorChannelWalkerError.ChannelRuleViolation(
                outcomeTypeName, errorsFieldName, 7, rule7));
        }
        String rule8 = ChannelRuleChecks.checkDuplicateMatchCriteria(mapped);
        if (rule8 != null) {
            errors.add(new ErrorChannelWalkerError.ChannelRuleViolation(
                outcomeTypeName, errorsFieldName, 8, rule8));
        }

        errors.addAll(HandlerAccessorCheck.check(
            outcomeTypeName, mapped, schema, codegenLoader, reflectTypeResolver));

        if (!errors.isEmpty()) {
            return new WalkerResult.Err<>(errors);
        }

        String mappingsConstantName = toScreamingSnake(outcomeTypeName);
        return new WalkerResult.Ok<>(new ErrorChannel.Mapped(mapped, mappingsConstantName));
    }

    /**
     * CamelCase to SCREAMING_SNAKE_CASE (e.g. {@code FilmPayload} -&gt; {@code FILM_PAYLOAD}).
     * Same transform as {@code BuildContext.toScreamingSnake}, kept here so the walker has no
     * package-private dependency on {@code BuildContext}.
     */
    private static String toScreamingSnake(String s) {
        if (s == null || s.isEmpty()) return s;
        var sb = new StringBuilder(s.length() + 4);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (i > 0 && Character.isUpperCase(c) && !Character.isUpperCase(s.charAt(i - 1))) {
                sb.append('_');
            }
            sb.append(Character.toUpperCase(c));
        }
        return sb.toString();
    }
}
