package no.sikt.graphitron.rewrite.model;

import graphql.language.SourceLocation;

/**
 * A field on the {@code Mutation} type. The only fields permitted to write to the database.
 */
public sealed interface MutationField extends RootField
    permits MutationField.MutationInsertTableField, MutationField.MutationUpdateTableField,
            MutationField.MutationDeleteTableField,
            MutationField.MutationUpsertTableField, MutationField.MutationServiceTableField,
            MutationField.MutationServiceRecordField {

    record MutationInsertTableField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef returnType
    ) implements MutationField {}

    record MutationUpdateTableField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef returnType
    ) implements MutationField {}

    record MutationDeleteTableField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef returnType
    ) implements MutationField {}

    record MutationUpsertTableField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef returnType
    ) implements MutationField {}

    /**
     * A mutation field backed by a developer-provided service method, returning a table-mapped type.
     *
     * <p>Parameter binding (including context arguments) is fully encoded in
     * {@link MethodRef#params()} via {@link ParamSource}.
     */
    record MutationServiceTableField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef.TableBoundReturnType returnType,
        MethodRef method
    ) implements MutationField {}

    /**
     * A mutation field backed by a developer-provided service method, returning a non-table type.
     *
     * <p>Parameter binding (including context arguments) is fully encoded in
     * {@link MethodRef#params()} via {@link ParamSource}.
     */
    record MutationServiceRecordField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef returnType,
        MethodRef method
    ) implements MutationField {}
}
