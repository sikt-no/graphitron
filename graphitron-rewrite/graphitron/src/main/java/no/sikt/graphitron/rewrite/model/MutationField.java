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
            MutationField.MutationServiceRecordField, MutationField.MutationDmlRecordField {

    /**
     * Sealed common supertype of the four DML mutation variants. Carries the per-field data the
     * INSERT / UPDATE / DELETE / UPSERT emitters share: the {@code @table} input argument that
     * drives the DML statement and a pre-resolved {@link DmlReturnExpression} arm that captures
     * the entire return-shape dispatch (encoded ID, projected {@code @table}, or {@code @record}
     * payload).
     *
     * <p>The classifier picks the {@link DmlReturnExpression} arm once; emitters pattern-match on
     * {@link #returnExpression()} with no {@code instanceof ScalarReturnType}, no
     * {@code wrapper().isList()} lookup, and no {@code Optional} unwrapping.
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
     * <p>{@code resultAssembly} carries the carrier-side success-arm wiring when the service
     * method's return type binds to a parameter of the SDL payload class's canonical constructor
     * (the "service returns the domain object" shape). Empty when the service method returns the
     * SDL payload class directly (legacy passthrough shape).
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
     * <p>{@code resultAssembly} carries the carrier-side success-arm wiring when the service
     * method's return type binds to a parameter of the SDL payload class's canonical constructor
     * (the "service returns the domain object" shape). Empty when the service method returns the
     * SDL payload class directly (legacy passthrough shape).
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

    /**
     * R75 / Phase 1 — a record-returning DML mutation: the schema field carries
     * {@code @mutation(typeName: INSERT|UPDATE|UPSERT)}, takes a {@code @table} input, and
     * returns a payload carrier (an SDL Object the
     * {@link no.sikt.graphitron.rewrite.BuildContext#tryResolveSingleRecordCarrier}
     * trigger resolves to {@code Ok}). Sibling to {@link DmlTableField}: the latter covers the
     * "direct @table return" shape ({@code createFilm: Film}), this covers the "payload wrap"
     * shape ({@code createFilm: CreateFilmPayload}). The carrier's data field is classified
     * as {@link ChildField.SingleRecordTableField}.
     *
     * <p>The {@code kind} discriminator drives per-DML-kind emit variation (INSERT vs UPDATE vs
     * UPSERT each have distinct SQL shapes); the model is one permit because the components are
     * identical across the three kinds. {@code kind == DELETE} is rejected at classify time, not
     * encoded here: DELETE-with-payload-return is incorrect by construction (the row is gone
     * before the response SELECT can read it). The compact-constructor invariant pins that
     * type-system guarantee.
     *
     * <p>{@link #returnType()} is the carrier's {@link ReturnTypeRef.ResultReturnType} (no
     * unwrap — the SDL's structural truth). {@link #tableInputArg()} carries the input
     * {@code @table} exactly like the existing {@link DmlTableField} permits do; the
     * emitter reads {@code tableInputArg.inputTable().primaryKeyColumns()} for the
     * PK-only {@code RETURNING} clause of the two-step DML.
     */
    record MutationDmlRecordField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef.ResultReturnType returnType,
        ArgumentRef.InputTypeArg.TableInputArg tableInputArg,
        DmlKind kind,
        Optional<ErrorChannel> errorChannel
    ) implements MutationField {

        public MutationDmlRecordField {
            if (kind == DmlKind.DELETE) {
                throw new IllegalArgumentException(
                    "MutationDmlRecordField cannot carry DmlKind.DELETE — DELETE-with-payload-return "
                    + "is rejected at classify time (returning pre-deletion state is incorrect by "
                    + "construction; the row is gone before the response SELECT can read it).");
            }
        }
    }
}
