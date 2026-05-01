package no.sikt.graphitron.rewrite.model;

import graphql.language.SourceLocation;
import no.sikt.graphitron.rewrite.ArgumentRef;

import java.util.Optional;

/**
 * A field on the {@code Mutation} type. The only fields permitted to write to the database.
 *
 * <p>Every variant is fetcher-emitting and therefore implements {@link WithErrorChannel};
 * when the carrier classifier resolves an {@code errors}-shaped field on the payload, the
 * channel is populated and the emitter wraps the fetcher body in a try/catch routing thrown
 * exceptions into the typed {@code errors} field. Until the C3 carrier classifier lands,
 * call sites pass {@link Optional#empty()}.
 */
public sealed interface MutationField extends RootField, WithErrorChannel
    permits MutationField.DmlTableField, MutationField.MutationServiceTableField,
            MutationField.MutationServiceRecordField {

    /**
     * Sealed common supertype of the four DML mutation variants. Carries the per-field data the
     * INSERT / UPDATE / DELETE / UPSERT emitters share: the {@code @table} input argument that
     * drives the DML statement, and the optional encode helper used when the return type is
     * {@code ScalarReturnType("ID")}. Introduced so {@code buildMutationReturnExpression} can
     * dispatch over a single supertype.
     *
     * <p>{@code encodeReturn} is {@link Optional#of(Object)} for {@code ScalarReturnType("ID")}
     * returns and {@link Optional#empty()} otherwise; the classifier guarantees this invariant
     * before constructing the variant. The {@link HelperRef.Encode} resolves to the per-type
     * {@code encode<TypeName>} helper on the generated {@code NodeIdEncoder}, so the emitter
     * never reaches back into {@code JooqCatalog} for typeId or key columns.
     */
    sealed interface DmlTableField extends MutationField
            permits MutationInsertTableField, MutationUpdateTableField,
                    MutationDeleteTableField, MutationUpsertTableField {
        ReturnTypeRef returnType();
        ArgumentRef.InputTypeArg.TableInputArg tableInputArg();
        Optional<HelperRef.Encode> encodeReturn();
        Optional<PayloadAssembly> payloadAssembly();
        SourceLocation location();
    }

    record MutationInsertTableField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef returnType,
        ArgumentRef.InputTypeArg.TableInputArg tableInputArg,
        Optional<HelperRef.Encode> encodeReturn,
        Optional<PayloadAssembly> payloadAssembly,
        Optional<ErrorChannel> errorChannel
    ) implements DmlTableField {}

    record MutationUpdateTableField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef returnType,
        ArgumentRef.InputTypeArg.TableInputArg tableInputArg,
        Optional<HelperRef.Encode> encodeReturn,
        Optional<PayloadAssembly> payloadAssembly,
        Optional<ErrorChannel> errorChannel
    ) implements DmlTableField {}

    record MutationDeleteTableField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef returnType,
        ArgumentRef.InputTypeArg.TableInputArg tableInputArg,
        Optional<HelperRef.Encode> encodeReturn,
        Optional<PayloadAssembly> payloadAssembly,
        Optional<ErrorChannel> errorChannel
    ) implements DmlTableField {}

    record MutationUpsertTableField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef returnType,
        ArgumentRef.InputTypeArg.TableInputArg tableInputArg,
        Optional<HelperRef.Encode> encodeReturn,
        Optional<PayloadAssembly> payloadAssembly,
        Optional<ErrorChannel> errorChannel
    ) implements DmlTableField {}

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
    ) implements MutationField, MethodBackedField {}

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
    ) implements MutationField, MethodBackedField {}
}
