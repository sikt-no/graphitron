package no.sikt.graphitron.rewrite.model;

import graphql.language.SourceLocation;
import no.sikt.graphitron.rewrite.ArgumentRef;
import no.sikt.graphitron.rewrite.JooqCatalog;

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
    permits MutationField.DmlTableField, MutationField.MutationServiceTableField,
            MutationField.MutationServiceRecordField {

    /** The typed-error channel resolved for this fetcher, when its payload carries an {@code errors} field. */
    Optional<ErrorChannel> errorChannel();

    /**
     * Sealed common supertype of the four DML mutation variants. Carries the per-field data the
     * INSERT / UPDATE / DELETE / UPSERT emitters share: the {@code @table} input argument that
     * drives the DML statement, and the optional NodeId metadata used when the return type is
     * {@code ScalarReturnType("ID")}. Introduced so {@code buildMutationReturnExpression} can
     * dispatch over a single supertype.
     *
     * <p>{@code nodeIdMeta} is {@link Optional#of(Object)} for {@code ScalarReturnType("ID")}
     * returns and {@link Optional#empty()} otherwise; the classifier guarantees this invariant
     * before constructing the variant.
     */
    sealed interface DmlTableField extends MutationField
            permits MutationInsertTableField, MutationUpdateTableField,
                    MutationDeleteTableField, MutationUpsertTableField {
        ReturnTypeRef returnType();
        ArgumentRef.InputTypeArg.TableInputArg tableInputArg();
        Optional<JooqCatalog.NodeIdMetadata> nodeIdMeta();
        SourceLocation location();
    }

    record MutationInsertTableField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef returnType,
        ArgumentRef.InputTypeArg.TableInputArg tableInputArg,
        Optional<JooqCatalog.NodeIdMetadata> nodeIdMeta,
        Optional<ErrorChannel> errorChannel
    ) implements DmlTableField {

        public MutationInsertTableField(String parentTypeName, String name, SourceLocation location,
                                         ReturnTypeRef returnType,
                                         ArgumentRef.InputTypeArg.TableInputArg tableInputArg,
                                         Optional<JooqCatalog.NodeIdMetadata> nodeIdMeta) {
            this(parentTypeName, name, location, returnType, tableInputArg, nodeIdMeta, Optional.empty());
        }
    }

    record MutationUpdateTableField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef returnType,
        ArgumentRef.InputTypeArg.TableInputArg tableInputArg,
        Optional<JooqCatalog.NodeIdMetadata> nodeIdMeta,
        Optional<ErrorChannel> errorChannel
    ) implements DmlTableField {

        public MutationUpdateTableField(String parentTypeName, String name, SourceLocation location,
                                         ReturnTypeRef returnType,
                                         ArgumentRef.InputTypeArg.TableInputArg tableInputArg,
                                         Optional<JooqCatalog.NodeIdMetadata> nodeIdMeta) {
            this(parentTypeName, name, location, returnType, tableInputArg, nodeIdMeta, Optional.empty());
        }
    }

    record MutationDeleteTableField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef returnType,
        ArgumentRef.InputTypeArg.TableInputArg tableInputArg,
        Optional<JooqCatalog.NodeIdMetadata> nodeIdMeta,
        Optional<ErrorChannel> errorChannel
    ) implements DmlTableField {

        public MutationDeleteTableField(String parentTypeName, String name, SourceLocation location,
                                         ReturnTypeRef returnType,
                                         ArgumentRef.InputTypeArg.TableInputArg tableInputArg,
                                         Optional<JooqCatalog.NodeIdMetadata> nodeIdMeta) {
            this(parentTypeName, name, location, returnType, tableInputArg, nodeIdMeta, Optional.empty());
        }
    }

    record MutationUpsertTableField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef returnType,
        ArgumentRef.InputTypeArg.TableInputArg tableInputArg,
        Optional<JooqCatalog.NodeIdMetadata> nodeIdMeta,
        Optional<ErrorChannel> errorChannel
    ) implements DmlTableField {

        public MutationUpsertTableField(String parentTypeName, String name, SourceLocation location,
                                         ReturnTypeRef returnType,
                                         ArgumentRef.InputTypeArg.TableInputArg tableInputArg,
                                         Optional<JooqCatalog.NodeIdMetadata> nodeIdMeta) {
            this(parentTypeName, name, location, returnType, tableInputArg, nodeIdMeta, Optional.empty());
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
