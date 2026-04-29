package no.sikt.graphitron.rewrite.model;

import graphql.language.SourceLocation;

import java.util.Optional;

/**
 * A field on the {@code Mutation} type. The only fields permitted to write to the database.
 *
 * <p>Every variant carries an {@code Optional<ErrorChannel>}; when present, the emitter wraps
 * the fetcher body in a try/catch that routes thrown exceptions into the payload's typed
 * {@code errors} field. The slot is populated by the carrier classifier (lands in the C3
 * pass of R12); for now it defaults to {@link Optional#empty()} via the no-channel
 * convenience constructor on each variant.
 */
public sealed interface MutationField extends RootField
    permits MutationField.MutationInsertTableField, MutationField.MutationUpdateTableField,
            MutationField.MutationDeleteTableField,
            MutationField.MutationUpsertTableField, MutationField.MutationServiceTableField,
            MutationField.MutationServiceRecordField {

    /** The typed-error channel resolved for this fetcher, when its payload carries an {@code errors} field. */
    Optional<ErrorChannel> errorChannel();

    record MutationInsertTableField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef returnType,
        Optional<ErrorChannel> errorChannel
    ) implements MutationField {

        public MutationInsertTableField(String parentTypeName, String name,
                                         SourceLocation location, ReturnTypeRef returnType) {
            this(parentTypeName, name, location, returnType, Optional.empty());
        }
    }

    record MutationUpdateTableField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef returnType,
        Optional<ErrorChannel> errorChannel
    ) implements MutationField {

        public MutationUpdateTableField(String parentTypeName, String name,
                                         SourceLocation location, ReturnTypeRef returnType) {
            this(parentTypeName, name, location, returnType, Optional.empty());
        }
    }

    record MutationDeleteTableField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef returnType,
        Optional<ErrorChannel> errorChannel
    ) implements MutationField {

        public MutationDeleteTableField(String parentTypeName, String name,
                                         SourceLocation location, ReturnTypeRef returnType) {
            this(parentTypeName, name, location, returnType, Optional.empty());
        }
    }

    record MutationUpsertTableField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef returnType,
        Optional<ErrorChannel> errorChannel
    ) implements MutationField {

        public MutationUpsertTableField(String parentTypeName, String name,
                                         SourceLocation location, ReturnTypeRef returnType) {
            this(parentTypeName, name, location, returnType, Optional.empty());
        }
    }

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
        MethodRef method,
        Optional<ErrorChannel> errorChannel
    ) implements MutationField, MethodBackedField {

        public MutationServiceTableField(String parentTypeName, String name, SourceLocation location,
                                          ReturnTypeRef.TableBoundReturnType returnType, MethodRef method) {
            this(parentTypeName, name, location, returnType, method, Optional.empty());
        }
    }

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
        MethodRef method,
        Optional<ErrorChannel> errorChannel
    ) implements MutationField, MethodBackedField {

        public MutationServiceRecordField(String parentTypeName, String name, SourceLocation location,
                                           ReturnTypeRef returnType, MethodRef method) {
            this(parentTypeName, name, location, returnType, method, Optional.empty());
        }
    }
}
