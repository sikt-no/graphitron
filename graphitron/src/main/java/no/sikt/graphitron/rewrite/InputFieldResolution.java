package no.sikt.graphitron.rewrite;

import graphql.language.SourceLocation;
import no.sikt.graphitron.rewrite.model.InputField;
import no.sikt.graphitron.model.diagnostics.Rejection;

/**
 * Result of classifying a single {@link graphql.schema.GraphQLInputObjectField} during the
 * write-target resolution pass (for DML inputs) or argument-classify pass (for filter inputs).
 *
 * <p>A field that resolves successfully yields a {@link Resolved} containing the classified
 * {@link InputField}. Failures yield {@link Unresolved} carrying the same typed
 * {@link Rejection} every sibling builder-step result carries, plus the failing field's own
 * {@link SourceLocation} so a consumer reports the fact where the author wrote it rather than at
 * the consuming coordinate.
 */
sealed interface InputFieldResolution
        permits InputFieldResolution.Resolved, InputFieldResolution.Unresolved {

    record Resolved(InputField field) implements InputFieldResolution {}

    record Unresolved(
        String fieldName,
        SourceLocation location,
        Rejection rejection
    ) implements InputFieldResolution {}
}
