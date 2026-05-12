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
            MutationField.MutationServiceRecordField, MutationField.MutationDmlRecordField,
            MutationField.MutationBulkDmlRecordField {

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

    /**
     * R141 — a record-returning DML mutation with bulk {@code @table} input and a list-shaped
     * {@code @table}-element data field on the carrier. The carrier itself is single
     * ({@code FilmsPayload}, not {@code [FilmsPayload!]!}); the list lives on the data field
     * ({@code films: [Film!]}). Sibling to {@link MutationDmlRecordField}: the latter covers
     * the singleton-data-field case ({@code Payload { film: Film }}, single input), this
     * covers the bulk-data-field case ({@code Payload { films: [Film!] }}, bulk input). The
     * carrier's data field is classified as {@link ChildField.SingleRecordTableField} with
     * {@link no.sikt.graphitron.rewrite.model.SourceKey.Cardinality#MANY}.
     *
     * <p>The classifier admits exactly
     * {@code (tableInputArg.list() == true, dataField.wrapper().isList() == true,
     * kind ∈ {INSERT, UPDATE})} and pairs the input cardinality to the data field's element
     * type via the existing load-bearing classifier check
     * {@code mutation-dml-record-field.data-table-equals-input-table}.
     *
     * <p>UPSERT is structurally compatible with this leaf but is refused upstream by
     * {@code MutationInputResolver} under R144's cardinality-safety regime. R145
     * ({@code mutation-cardinality-safety-upsert}) lifts the refusal with a designed
     * cardinality story; at that point this leaf's compact-constructor relaxes to admit
     * {@code DmlKind.UPSERT} and the parameterised emitter gains the UPSERT branch.
     *
     * <p><b>Order preservation invariant.</b> {@code output.data[i]} corresponds to
     * {@code input[i]} for all {@code i ∈ [0, N)}. The emitter satisfies the invariant via
     * batched per-row DML inside one transaction (N+1 statements), collecting PKs in input
     * order and iterating a PK-keyed map of the response-SELECT in that order to build the
     * response. The runtime audit is {@code DmlBulkMutationsExecutionTest}'s N=3 deliberately-
     * non-PK-ordered round-trip. Any future single-statement emit refinement (e.g. an
     * ordinal-preserving Postgres contract) must preserve the same input-order assertion the
     * round-trip test makes. The {@code @see} pointer between this record and
     * {@link no.sikt.graphitron.rewrite.generators.TypeFetcherGenerator}'s
     * {@code buildMutationBulkDmlRecordFetcher} is the find-usages anchor; the invariant is
     * not encoded as a load-bearing classifier check because the contract is a runtime claim
     * about emit-order iteration with no compile-time signal under any classifier relaxation,
     * and overloading {@code @LoadBearingClassifierCheck} for navigability-only contracts
     * dilutes the audit's classifier→emitter shape-contract signal.
     *
     * <p><b>Per-kind emit variation.</b> INSERT and UPDATE differ on the per-row statement
     * and the WHERE/SET clauses; future UPSERT lifts at R145 add a third shape with
     * ON CONFLICT semantics. The components of this record are the same across kinds today,
     * but the emit shapes are not; the principles-aligned target is sealed-on-kind permits
     * mirroring {@link DmlTableField}, tracked at {@code dml-record-carrier-sealed-on-kind}
     * as the joint lift over both record-carrier leaves. Until that lift lands, the
     * {@link DmlKind} enum field encodes the per-emit-shape dispatch and the parameterised
     * emitter switches on it.
     *
     * <p><b>DELETE rejection.</b> Mirrors {@link MutationDmlRecordField}: DELETE-with-payload-
     * return is incorrect by construction (the row is gone before the response SELECT can
     * read it). The compact-constructor invariant is belt-and-braces under the upstream
     * classifier check that already rejects DELETE before this record is constructed.
     *
     * @see no.sikt.graphitron.rewrite.generators.TypeFetcherGenerator
     */
    record MutationBulkDmlRecordField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef.ResultReturnType returnType,
        ArgumentRef.InputTypeArg.TableInputArg tableInputArg,
        DmlKind kind,
        Optional<ErrorChannel> errorChannel
    ) implements MutationField {

        public MutationBulkDmlRecordField {
            if (kind == DmlKind.DELETE) {
                throw new IllegalArgumentException(
                    "MutationBulkDmlRecordField cannot carry DmlKind.DELETE — "
                    + "DELETE-with-payload-return is rejected at classify time "
                    + "(returning pre-deletion state is incorrect by construction; the row "
                    + "is gone before the response SELECT can read it).");
            }
            if (kind == DmlKind.UPSERT) {
                throw new IllegalArgumentException(
                    "MutationBulkDmlRecordField cannot carry DmlKind.UPSERT under R144's "
                    + "cardinality-safety regime — UPSERT is refused at the upstream "
                    + "MutationInputResolver and lifts via R145 "
                    + "(mutation-cardinality-safety-upsert).");
            }
            if (!tableInputArg.list()) {
                throw new IllegalArgumentException(
                    "MutationBulkDmlRecordField requires bulk @table input "
                    + "(tableInputArg.list() == true); single-input belongs on "
                    + "MutationDmlRecordField.");
            }
        }
    }
}
