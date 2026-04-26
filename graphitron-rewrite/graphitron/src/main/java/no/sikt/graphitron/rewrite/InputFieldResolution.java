package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.model.InputField;

/**
 * Result of classifying a single {@link graphql.schema.GraphQLInputObjectField} during the
 * type-build pass (for {@code @table} inputs) or argument-classify pass (for plain inputs).
 *
 * <p>A field that resolves successfully yields a {@link Resolved} containing the classified
 * {@link InputField}. Failures yield {@link Unresolved} with diagnostic details.
 *
 * <p>{@link Unresolved#lookupColumn()} carries the SQL column name that was attempted, when the
 * failure was a column-miss — used by the type builder to generate a "did you mean" hint.
 */
sealed interface InputFieldResolution
        permits InputFieldResolution.Resolved, InputFieldResolution.Unresolved {

    record Resolved(InputField field) implements InputFieldResolution {}

    record Unresolved(
        String fieldName,
        String lookupColumn,
        String reason
    ) implements InputFieldResolution {}
}
