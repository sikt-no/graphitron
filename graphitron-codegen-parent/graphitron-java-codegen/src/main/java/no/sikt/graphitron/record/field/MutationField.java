package no.sikt.graphitron.record.field;

/**
 * A field on the {@code Mutation} type. The only fields permitted to write to the database.
 */
public sealed interface MutationField extends RootField
    permits InsertMutationField, UpdateMutationField, DeleteMutationField,
            UpsertMutationField, ServiceMutationField {}
