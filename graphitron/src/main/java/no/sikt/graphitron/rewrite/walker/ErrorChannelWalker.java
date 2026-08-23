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
 * Resolves the {@link ErrorChannel.Mapped} carrier for an in-scope {@code @service}
 * outcome field from its classified {@link OutcomeType}. The output-walking
 * analogue of {@link ServiceMethodCallWalker}, over a different SDL surface (the outcome type and
 * its errors field rather than the field's arguments).
 *
 * <p>The {@link no.sikt.graphitron.rewrite.model.ChildField.ErrorsField} already carries the
 * flattened {@code @error} type list (single type, union members, or interface implementations)
 * in source order, so the walker reads it rather than re-scanning the SDL.
 *
 * <p>Errors collect across stages rather than short-circuiting. {@link WalkerResult.Ok} carries the
 * {@link ErrorChannel.Mapped}; {@link WalkerResult.Err} carries the typed
 * {@link ErrorChannelWalkerError} arms, and the orchestrator drops the field from the classified
 * set (no fallback to {@link no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField}).
 *
 * <p>The walker stamps the bare mappings-constant name; the build-scoped, post-classification
 * {@link no.sikt.graphitron.rewrite.MappingsConstantNameDedup} pass rewrites collisions downstream.
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

        String handlerCardinality = ChannelRuleChecks.checkMultiValidation(mapped);
        if (handlerCardinality != null) {
            errors.add(new ErrorChannelWalkerError.ChannelRuleViolation(
                outcomeTypeName, errorsFieldName, 7, handlerCardinality));
        }
        String duplicateMatchCriteria = ChannelRuleChecks.checkDuplicateMatchCriteria(mapped);
        if (duplicateMatchCriteria != null) {
            errors.add(new ErrorChannelWalkerError.ChannelRuleViolation(
                outcomeTypeName, errorsFieldName, 8, duplicateMatchCriteria));
        }

        errors.addAll(HandlerAccessorCheck.check(
            outcomeTypeName, mapped, schema, codegenLoader, reflectTypeResolver));

        if (!errors.isEmpty()) {
            return new WalkerResult.Err<>(errors);
        }

        String mappingsConstantName =
            no.sikt.graphitron.plan.GeneratedUnits.mappingsConstant(outcomeTypeName);
        return new WalkerResult.Ok<>(new ErrorChannel.Mapped(mapped, mappingsConstantName));
    }
}
