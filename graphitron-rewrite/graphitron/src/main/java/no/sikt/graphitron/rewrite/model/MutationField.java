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
     * drives the DML statement and a pre-resolved {@link DmlReturnExpression} arm that captures
     * the entire return-shape dispatch (encoded ID, projected {@code @table}, or R12 payload).
     *
     * <p>Phase 1B of {@code graphitron-rewrite/roadmap/mutations.md} replaced the broad
     * {@code (returnType: ReturnTypeRef, encodeReturn: Optional<HelperRef.Encode>,
     * payloadAssembly: Optional<PayloadAssembly>)} triple with a single
     * {@link DmlReturnExpression} slot. The classifier picks the arm once; emitters
     * pattern-match on {@link #returnExpression()} with no {@code instanceof ScalarReturnType},
     * no {@code wrapper().isList()} lookup, no {@code Optional.orElseThrow()}, and no
     * {@code payloadAssembly().isPresent()} predicate.
     */
    sealed interface DmlTableField extends MutationField
            permits MutationInsertTableField, MutationUpdateTableField,
                    MutationDeleteTableField, MutationUpsertTableField {
        DmlReturnExpression returnExpression();
        ArgumentRef.InputTypeArg.TableInputArg tableInputArg();
        SourceLocation location();
    }

    record MutationInsertTableField(
        String parentTypeName,
        String name,
        SourceLocation location,
        DmlReturnExpression returnExpression,
        ArgumentRef.InputTypeArg.TableInputArg tableInputArg,
        Optional<ErrorChannel> errorChannel
    ) implements DmlTableField {}

    record MutationUpdateTableField(
        String parentTypeName,
        String name,
        SourceLocation location,
        DmlReturnExpression returnExpression,
        ArgumentRef.InputTypeArg.TableInputArg tableInputArg,
        Optional<ErrorChannel> errorChannel
    ) implements DmlTableField {}

    record MutationDeleteTableField(
        String parentTypeName,
        String name,
        SourceLocation location,
        DmlReturnExpression returnExpression,
        ArgumentRef.InputTypeArg.TableInputArg tableInputArg,
        Optional<ErrorChannel> errorChannel
    ) implements DmlTableField {}

    record MutationUpsertTableField(
        String parentTypeName,
        String name,
        SourceLocation location,
        DmlReturnExpression returnExpression,
        ArgumentRef.InputTypeArg.TableInputArg tableInputArg,
        Optional<ErrorChannel> errorChannel
    ) implements DmlTableField {}

    /**
     * A mutation field backed by a developer-provided service method, returning a table-mapped type.
     *
     * <p>Parameter binding (including context arguments) is fully encoded in
     * {@link MethodRef#params()} via {@link ParamSource}.
     *
     * <p>{@code resultAssembly} carries the carrier-side success-arm wiring (R12 §2c, §5) when
     * the service method's return type binds to a parameter of the SDL payload class's
     * canonical constructor (the "service returns the domain object" shape). Empty when the
     * service method returns the SDL payload class directly (legacy passthrough shape).
     */
    record MutationServiceTableField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef.TableBoundReturnType returnType,
        MethodRef method,
        Optional<ErrorChannel> errorChannel,
        Optional<ResultAssembly> resultAssembly
    ) implements MutationField, MethodBackedField {}

    /**
     * A mutation field backed by a developer-provided service method, returning a non-table type.
     *
     * <p>Parameter binding (including context arguments) is fully encoded in
     * {@link MethodRef#params()} via {@link ParamSource}.
     *
     * <p>{@code resultAssembly} carries the carrier-side success-arm wiring (R12 §2c, §5) when
     * the service method's return type binds to a parameter of the SDL payload class's
     * canonical constructor (the "service returns the domain object" shape). Empty when the
     * service method returns the SDL payload class directly (legacy passthrough shape).
     */
    record MutationServiceRecordField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef returnType,
        MethodRef method,
        Optional<ErrorChannel> errorChannel,
        Optional<ResultAssembly> resultAssembly
    ) implements MutationField, MethodBackedField {}
}
