package no.sikt.graphitron.rewrite.field;

import graphql.language.SourceLocation;

import java.util.List;

/**
 * A field on the {@code Mutation} type. The only fields permitted to write to the database.
 */
public sealed interface MutationField extends RootField
    permits MutationField.MutationInsertTableField, MutationField.MutationUpdateTableField, MutationField.MutationDeleteTableField,
            MutationField.MutationUpsertTableField, MutationField.MutationServiceTableField, MutationField.MutationServiceRecordField {

    /**
     * A mutation field for {@code @mutation(typeName: INSERT)}.
     *
     * <p>{@code returnType} is the resolved outcome of looking up the return type in the classified
     * schema.
     *
     * <p>{@code arguments} is the full list of GraphQL arguments on the field.
     */
    record MutationInsertTableField(
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
    record MutationUpdateTableField(
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
    record MutationDeleteTableField(
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
    record MutationUpsertTableField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef returnType,
        List<ArgumentSpec> arguments
    ) implements MutationField {}

    /**
     * A mutation field delegating to a developer-provided service class via {@code @service},
     * where the return type is annotated with {@code @table} (service → table-mapped target).
     *
     * <p>{@code returnType} is narrowed to {@link ReturnTypeRef.TableBoundReturnType}.
     *
     * <p>{@code serviceRef} is the {@code service: ExternalCodeReference!} argument of the
     * {@code @service} directive — the Java class and method to delegate to.
     *
     * <p>{@code arguments} is the full list of GraphQL arguments on the field.
     *
     * <p>{@code contextArguments} is the list of strings from the {@code contextArguments} parameter
     * of the {@code @service} directive.
     */
    record MutationServiceTableField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef.TableBoundReturnType returnType,
        ExternalRef serviceRef,
        List<ArgumentSpec> arguments,
        List<String> contextArguments
    ) implements MutationField {}

    /**
     * A mutation field delegating to a developer-provided service class via {@code @service},
     * where the return type is NOT table-mapped (service → record/scalar target).
     *
     * <p>{@code serviceRef}, {@code arguments}, and {@code contextArguments} have the same semantics
     * as {@link MutationServiceTableField}.
     */
    record MutationServiceRecordField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef returnType,
        ExternalRef serviceRef,
        List<ArgumentSpec> arguments,
        List<String> contextArguments
    ) implements MutationField {}
}
