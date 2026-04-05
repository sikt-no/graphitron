package no.sikt.graphitron.record.field;

import graphql.language.SourceLocation;

/**
 * A field on the {@code Mutation} type. The only fields permitted to write to the database.
 */
public sealed interface MutationField extends RootField
    permits MutationField.InsertMutationField, MutationField.UpdateMutationField, MutationField.DeleteMutationField,
            MutationField.UpsertMutationField, MutationField.ServiceMutationField {

    /**
     * A mutation field for {@code @mutation(typeName: INSERT)}.
     *
     * <p>{@code returnType} is the resolved outcome of looking up the return type in the classified
     * schema.
     */
    record InsertMutationField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef returnType
    ) implements MutationField {}

    /**
     * A mutation field for {@code @mutation(typeName: UPDATE)}.
     *
     * <p>{@code returnType} is the resolved outcome of looking up the return type in the classified
     * schema.
     */
    record UpdateMutationField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef returnType
    ) implements MutationField {}

    /**
     * A mutation field for {@code @mutation(typeName: DELETE)}. Deleted rows are not re-queried.
     *
     * <p>{@code returnType} is the resolved outcome of looking up the return type in the classified
     * schema.
     */
    record DeleteMutationField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef returnType
    ) implements MutationField {}

    /**
     * A mutation field for {@code @mutation(typeName: UPSERT)}.
     *
     * <p>{@code returnType} is the resolved outcome of looking up the return type in the classified
     * schema.
     */
    record UpsertMutationField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef returnType
    ) implements MutationField {}

    /**
     * A mutation field delegating to a developer-provided service class via {@code @service}.
     *
     * <p>{@code returnType} is the resolved outcome of looking up the return type in the classified
     * schema.
     */
    record ServiceMutationField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef returnType
    ) implements MutationField {}
}
