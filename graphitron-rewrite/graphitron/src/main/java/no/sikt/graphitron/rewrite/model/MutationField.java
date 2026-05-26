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

    /**
     * R204: the DML mutation's emit shape is {@link DmlReturnExpression}-keyed. {@code Encoded*}
     * arms (ID-return) emit an encoded {@code String} at {@code env.getSource()}; {@code Projected*}
     * arms (@table-return) emit a sparse {@code RecordN<...>} projection on the table's PK
     * columns. Picked at the validator's group-by step so DML siblings reaching the same SDL ID
     * return type agree with the column-encoded NodeId producers also returning ID.
     */
    private static DomainReturnType dmlDomainReturnType(
            DmlReturnExpression expr,
            ArgumentRef.InputTypeArg.TableInputArg tableInputArg) {
        return switch (expr) {
            case DmlReturnExpression.EncodedSingle ignored -> new DomainReturnType.Plain(OutputField.STRING_CLASS);
            case DmlReturnExpression.EncodedList ignored   -> new DomainReturnType.Plain(OutputField.STRING_CLASS);
            case DmlReturnExpression.ProjectedSingle ignored -> new DomainReturnType.Record(tableInputArg.inputTable());
            case DmlReturnExpression.ProjectedList ignored   -> new DomainReturnType.Record(tableInputArg.inputTable());
        };
    }

    record MutationInsertTableField(
        String parentTypeName,
        String name,
        SourceLocation location,
        DmlReturnExpression returnExpression,
        ArgumentRef.InputTypeArg.TableInputArg tableInputArg,
        Optional<ErrorChannel> errorChannel
    ) implements DmlTableField {
        @Override public DomainReturnType domainReturnType() {
            return dmlDomainReturnType(returnExpression, tableInputArg);
        }
    }

    record MutationUpdateTableField(
        String parentTypeName,
        String name,
        SourceLocation location,
        DmlReturnExpression returnExpression,
        ArgumentRef.InputTypeArg.TableInputArg tableInputArg,
        Optional<ErrorChannel> errorChannel
    ) implements DmlTableField {
        @Override public DomainReturnType domainReturnType() {
            return dmlDomainReturnType(returnExpression, tableInputArg);
        }
    }

    record MutationDeleteTableField(
        String parentTypeName,
        String name,
        SourceLocation location,
        DmlReturnExpression returnExpression,
        ArgumentRef.InputTypeArg.TableInputArg tableInputArg,
        Optional<ErrorChannel> errorChannel
    ) implements DmlTableField {
        @Override public DomainReturnType domainReturnType() {
            return dmlDomainReturnType(returnExpression, tableInputArg);
        }
    }

    record MutationUpsertTableField(
        String parentTypeName,
        String name,
        SourceLocation location,
        DmlReturnExpression returnExpression,
        ArgumentRef.InputTypeArg.TableInputArg tableInputArg,
        Optional<ErrorChannel> errorChannel
    ) implements DmlTableField {
        @Override public DomainReturnType domainReturnType() {
            return dmlDomainReturnType(returnExpression, tableInputArg);
        }
    }

    /**
     * A mutation field backed by a developer-provided service method, returning a table-mapped type.
     *
     * <p>Parameter binding (including context arguments) is fully encoded in
     * {@link MethodRef#params()} via {@link ParamSource}.
     *
     * <p>The success arm is universal passthrough: the service method returns the SDL payload
     * class (or table-bound record) directly, and per-field wiring projects SDL fields off the
     * parent's domain return.
     */
    record MutationServiceTableField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef.TableBoundReturnType returnType,
        MethodRef method,
        Optional<ErrorChannel> errorChannel
    ) implements MutationField, MethodBackedField {
        /**
         * R204: see {@link ChildField.ServiceTableField#domainReturnType()} — the typed
         * {@code XRecord} is consumer-equivalent to a {@code Record(table)} via subtyping.
         */
        @Override public DomainReturnType domainReturnType() {
            return new DomainReturnType.Record(returnType.table());
        }
    }

    /**
     * A mutation field backed by a developer-provided service method, returning a non-table type.
     *
     * <p>Parameter binding (including context arguments) is fully encoded in
     * {@link MethodRef#params()} via {@link ParamSource}.
     *
     * <p>The success arm is universal passthrough: the service method returns the SDL payload
     * class (or scalar / pojo) directly, and per-field wiring projects SDL fields off the
     * parent's domain return.
     */
    record MutationServiceRecordField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef returnType,
        MethodRef method,
        Optional<ErrorChannel> errorChannel
    ) implements MutationField, MethodBackedField {
        /**
         * R204: the carrier-shape case ({@code @service} mutation returning {@code XRecord} or
         * {@code List<XRecord>} for an SDL payload whose single {@code @table}-typed data field's
         * record class equals the reflected return-element) puts a typed {@code XRecord} verbatim
         * at {@code env.getSource()}. The arm answer is {@link DomainReturnType.TableRecord}; the
         * payload {@link no.sikt.graphitron.javapoet.ClassName} is peeled from
         * {@link MethodRef#returnType()}'s parameterised shape. For non-carrier service shapes
         * (return-element is not a jOOQ {@code TableRecord} subclass), the arm choice is still
         * {@code TableRecord} — the validator's structural equality groups producers by SDL return
         * type, and only the carrier-shape conflict against {@link MutationDmlRecordField} /
         * {@link MutationBulkDmlRecordField}'s {@link DomainReturnType.Record} arm is surfaced;
         * other arrangements either agree (single producer per SDL type) or were already filtered
         * by upstream classifier rejections.
         */
        @Override public DomainReturnType domainReturnType() {
            return new DomainReturnType.TableRecord(OutputField.peelToClassName(method.returnType()));
        }
    }

    /**
     * R75 / Phase 1 — a record-returning DML mutation: the schema field carries
     * {@code @mutation(typeName: INSERT|UPDATE|UPSERT)}, takes a {@code @table} input, and
     * returns a payload carrier (an SDL Object admitted by
     * {@code BuildContext.scanStructuralDmlPayload} as a single non-errors data field whose
     * element is an {@code @table}-bound type). Sibling to {@link DmlTableField}: the latter
     * covers the "direct @table return" shape ({@code createFilm: Film}), this covers the
     * "payload wrap" shape ({@code createFilm: CreateFilmPayload}). The carrier's data field is
     * classified as {@link ChildField.SingleRecordTableField}.
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
            // R156: DELETE is admitted. The per-field carrier on the payload's data field is a
            // SingleRecordIdFieldFromReturning (encoded PK echo) or
            // SingleRecordTableFieldFromReturning (PK-only RETURNING projected through a sealed
            // PkResolution switch); both fetcher paths consume the RETURNING record directly,
            // so no follow-up SELECT runs after the row is gone. The DELETE-admissibility
            // decision is enforced at FieldBuilder's @mutation classifier (post-R178) on the
            // DmlElementKind dispatch returned by BuildContext.scanStructuralDmlPayload.
        }
        @Override public DomainReturnType domainReturnType() {
            return new DomainReturnType.Record(tableInputArg.inputTable());
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
     * type. The data table / input table agreement is now structurally pinned by the
     * {@link ProducerBinding.DmlEmitted} compact constructor's
     * {@code reflectedClass.getName().equals(tableRef.recordClass().reflectionName())}
     * invariant, surfaced via {@link Rejection.AuthorError.RecordBindingMultiProducer} when
     * disagreeing producers fold against the same SDL payload type.
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
     * order into a {@code Result<RecordN<PK>>}. The downstream data-field fetcher
     * ({@link no.sikt.graphitron.rewrite.generators.FetcherEmitter}'s
     * {@code buildSingleRecordTableFetcherValue} {@code Cardinality.MANY} arm) then builds a
     * PK-keyed map of the response-SELECT result and iterates {@code source.getValues(PK)}
     * (the input-ordered PK list) to project rows in input order. Input order is therefore
     * a property of the emitted Java code, not of the SQL planner's choice of scan strategy
     * for {@code WHERE pk IN (...)}. The runtime audit is {@code DmlBulkMutationsExecutionTest}'s
     * N=3 deliberately-non-PK-ordered round-trip. Any future single-statement emit refinement
     * (e.g. an ordinal-preserving Postgres contract) must preserve the same input-order
     * assertion the round-trip test makes. The {@code @see} pointer between this record and
     * {@link no.sikt.graphitron.rewrite.generators.TypeFetcherGenerator}'s
     * {@code buildMutationBulkDmlRecordFetcher} is the find-usages anchor; the contract is a
     * runtime claim about emit-order iteration with no compile-time signal.
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
     * <p><b>DELETE admission (R156).</b> Mirrors {@link MutationDmlRecordField}: DELETE is
     * admitted, with the per-field data-field carrier classified as
     * {@link ChildField.SingleRecordIdFieldFromReturning} or
     * {@link ChildField.SingleRecordTableFieldFromReturning} (no follow-up SELECT — the row is
     * gone, the encoded PK or PkResolution projection is consumed directly off the RETURNING
     * record). The DELETE-admissibility decision is enforced at FieldBuilder's @mutation
     * classifier (post-R178) on the DmlElementKind dispatch returned by
     * {@code BuildContext.scanStructuralDmlPayload}; this record's compact constructor admits
     * all four {@link DmlKind} values today (with UPSERT deferred per R145).
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
            // R156: DELETE is admitted. Same reasoning as MutationDmlRecordField — the
            // per-field carrier on the payload's data field is a SingleRecordIdFieldFromReturning
            // or SingleRecordTableFieldFromReturning, both reading the RETURNING record directly
            // with no follow-up SELECT.
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
        @Override public DomainReturnType domainReturnType() {
            return new DomainReturnType.Record(tableInputArg.inputTable());
        }
    }
}
