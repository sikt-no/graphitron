package no.sikt.graphitron.rewrite.model;

import graphql.language.SourceLocation;

import java.util.List;

import no.sikt.graphitron.rewrite.model.ArgumentRef;

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
        List<ArgumentRef> arguments
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
        List<ArgumentRef> arguments
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
        List<ArgumentRef> arguments
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
        List<ArgumentRef> arguments
    ) implements MutationField {}

    /**
     * A mutation field delegating to a developer-provided service class via {@code @service},
     * where the return type is annotated with {@code @table} (service → table-mapped target).
     *
     * <p>{@code returnType} is narrowed to {@link ReturnTypeRef.TableBoundReturnType}.
     *
     * <p>{@code method} carries the class name, method name, and reflected parameter list of the
     * service method, captured at parse time. If reflection failed the containing field is classified
     * as {@link no.sikt.graphitron.rewrite.model.UnclassifiedField} by the builder.
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
        List<ArgumentRef> arguments,
        List<String> contextArguments,
        MethodRef method
    ) implements MutationField {}

    /**
     * A mutation field delegating to a developer-provided service class via {@code @service},
     * where the return type is NOT table-mapped (service → record/scalar target).
     *
     * <p>{@code arguments}, {@code contextArguments}, and {@code method} have the same semantics
     * as {@link MutationServiceTableField}.
     */
    record MutationServiceRecordField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef returnType,
        List<ArgumentRef> arguments,
        List<String> contextArguments,
        MethodRef method
    ) implements MutationField {}
}
