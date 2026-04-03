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
     */
    record InsertMutationField(
        String parentTypeName,
        String name,
        SourceLocation location
    ) implements MutationField {}

    /**
     * A mutation field for {@code @mutation(typeName: UPDATE)}.
     */
    record UpdateMutationField(
        String parentTypeName,
        String name,
        SourceLocation location
    ) implements MutationField {}

    /**
     * A mutation field for {@code @mutation(typeName: DELETE)}. Deleted rows are not re-queried.
     */
    record DeleteMutationField(
        String parentTypeName,
        String name,
        SourceLocation location
    ) implements MutationField {}

    /**
     * A mutation field for {@code @mutation(typeName: UPSERT)}.
     */
    record UpsertMutationField(
        String parentTypeName,
        String name,
        SourceLocation location
    ) implements MutationField {}

    /**
     * A mutation field delegating to a developer-provided service class via {@code @service}.
     */
    record ServiceMutationField(
        String parentTypeName,
        String name,
        SourceLocation location
    ) implements MutationField {}
}
