package no.sikt.graphitron.record.field;

import graphql.language.SourceLocation;

import java.util.List;

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
     *
     * <p>{@code arguments} is the full list of GraphQL arguments on the field.
     */
    record InsertMutationField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef returnType,
        List<ArgumentSpec> arguments
    ) implements MutationField {}

    /**
     * A mutation field for {@code @mutation(typeName: UPDATE)}.
     *
     * <p>{@code returnType} is the resolved outcome of looking up the return type in the classified
     * schema.
     *
     * <p>{@code arguments} is the full list of GraphQL arguments on the field.
     */
    record UpdateMutationField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef returnType,
        List<ArgumentSpec> arguments
    ) implements MutationField {}

    /**
     * A mutation field for {@code @mutation(typeName: DELETE)}. Deleted rows are not re-queried.
     *
     * <p>{@code returnType} is the resolved outcome of looking up the return type in the classified
     * schema.
     *
     * <p>{@code arguments} is the full list of GraphQL arguments on the field.
     */
    record DeleteMutationField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef returnType,
        List<ArgumentSpec> arguments
    ) implements MutationField {}

    /**
     * A mutation field for {@code @mutation(typeName: UPSERT)}.
     *
     * <p>{@code returnType} is the resolved outcome of looking up the return type in the classified
     * schema.
     *
     * <p>{@code arguments} is the full list of GraphQL arguments on the field.
     */
    record UpsertMutationField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef returnType,
        List<ArgumentSpec> arguments
    ) implements MutationField {}

    /**
     * A mutation field delegating to a developer-provided service class via {@code @service}.
     *
     * <p>{@code returnType} is the resolved outcome of looking up the return type in the classified
     * schema.
     *
     * <p>{@code serviceRef} is the {@code service: ExternalCodeReference!} argument of the
     * {@code @service} directive — the Java class and method to delegate to.
     *
     * <p>{@code contextArguments} is the list of strings from the {@code contextArguments} parameter
     * of the {@code @service} directive.
     *
     * <p>{@code arguments} is the full list of GraphQL arguments on the field.
     */
    record ServiceMutationField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef returnType,
        ExternalRef serviceRef,
        List<ArgumentSpec> arguments,
        List<String> contextArguments
    ) implements MutationField {}
}
