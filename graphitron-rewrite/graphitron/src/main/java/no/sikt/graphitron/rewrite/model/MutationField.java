package no.sikt.graphitron.rewrite.model;

import graphql.language.SourceLocation;
import no.sikt.graphitron.rewrite.ArgumentRef;

import java.util.List;
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
            MutationField.MutationServiceRecordField, MutationField.MutationServicePolymorphicField,
            MutationField.MutationServiceTableInterfaceField,
            MutationField.MutationDmlRecordField,
            MutationField.MutationBulkDmlRecordField,
            MutationField.MutationUpdatePayloadField, MutationField.MutationBulkUpdatePayloadField,
            MutationField.MutationDeletePayloadField, MutationField.MutationBulkDeletePayloadField {

    /** Every {@code MutationField} leaf is on the {@code Mutation} root, so the source is {@link Source.Root.Mutation}. */
    @Override default Source source() { return new Source.Root.Mutation(); }

    @Override default Operation operation() {
        return switch (this) {
            // DML write: the verb is the leaf identity; the arm carries the leaf's input surface.
            case MutationInsertTableField f -> new Operation.Insert(f.tableInputArg());
            case MutationUpsertTableField f -> new Operation.Upsert(f.tableInputArg());
            case MutationUpdateTableField f -> new Operation.Update(f.inputArg(), f.updateRows());
            case MutationDeleteTableField f -> new Operation.Delete(f.inputArg(), f.deleteRows());
            case MutationServiceTableField f -> OutputField.serviceCall(f.serviceMethodCall());
            case MutationServiceRecordField f -> OutputField.serviceCall(f.serviceMethodCall());
            case MutationServicePolymorphicField f -> OutputField.serviceCall(f.serviceMethodCall());
            case MutationServiceTableInterfaceField f -> OutputField.serviceCall(f.serviceMethodCall());
            // The record-backed DML carriers read their verb off the DmlKind discriminator.
            case MutationDmlRecordField f -> dmlOperation(f.kind(), f.tableInputArg());
            case MutationBulkDmlRecordField f -> dmlOperation(f.kind(), f.tableInputArg());
            // Payload wrappers source their SET/WHERE partition from the walker carrier; verb is the leaf identity.
            case MutationUpdatePayloadField f -> new Operation.Update(f.inputArg(), f.updateRows());
            case MutationBulkUpdatePayloadField f -> new Operation.Update(f.inputArg(), f.updateRows());
            case MutationDeletePayloadField f -> new Operation.Delete(f.inputArg(), f.deleteRows());
            case MutationBulkDeletePayloadField f -> new Operation.Delete(f.inputArg(), f.deleteRows());
        };
    }

    @Override default Target target() {
        return switch (this) {
            // The return-shape slot (DmlReturnExpression) encodes both wrapper and shape: Column
            // (encoded ID) vs Table (in-fetcher follow-up SELECT). The follow-up itself is the derived
            // re-fetch, not a tuple axis.
            case MutationInsertTableField f -> OutputField.dmlTarget(f.returnExpression());
            case MutationUpdateTableField f -> OutputField.dmlTarget(f.returnExpression());
            case MutationDeleteTableField f -> OutputField.dmlTarget(f.returnExpression());
            case MutationUpsertTableField f -> OutputField.dmlTarget(f.returnExpression());
            case MutationServiceTableField f -> OutputField.wrap(f.returnType().wrapper(), new TargetShape.Table());
            case MutationServiceRecordField f -> OutputField.listOrSingle(f.returnType().wrapper(), new TargetShape.Record());
            // Interface-only service-polymorphic return (union/table-interface rejected at classify).
            case MutationServicePolymorphicField f -> OutputField.wrap(f.returnType().wrapper(), new TargetShape.Interface());
            // Single-table service interface return (R405): raw Record / List<Record> routed by the
            // discriminated TypeResolver; Interface (not Table) keeps requiresReFetch() false so the
            // re-fetch mirror agrees with the service fetcher's own by-PK re-projection.
            case MutationServiceTableInterfaceField f -> OutputField.wrap(f.returnType().wrapper(), new TargetShape.Interface());
            case MutationDmlRecordField f -> OutputField.listOrSingle(f.returnType().wrapper(), new TargetShape.Record());
            case MutationBulkDmlRecordField f -> OutputField.listOrSingle(f.returnType().wrapper(), new TargetShape.Record());
            case MutationUpdatePayloadField f -> OutputField.listOrSingle(f.returnType().wrapper(), new TargetShape.Record());
            case MutationBulkUpdatePayloadField f -> OutputField.listOrSingle(f.returnType().wrapper(), new TargetShape.Record());
            case MutationDeletePayloadField f -> OutputField.listOrSingle(f.returnType().wrapper(), new TargetShape.Record());
            case MutationBulkDeletePayloadField f -> OutputField.listOrSingle(f.returnType().wrapper(), new TargetShape.Record());
        };
    }

    /**
     * Maps the record-backed DML carrier's {@link DmlKind} discriminator to its write
     * {@link Operation} arm. The carriers' compact constructors reject UPDATE / DELETE (carved off
     * onto the walker-carrier payload leaves), so the live range is {@code {INSERT, UPSERT}}; the
     * UPDATE / DELETE arms here are unreachable backstops keeping the switch exhaustive.
     */
    private static Operation dmlOperation(DmlKind kind, ArgumentRef.InputTypeArg.TableInputArg input) {
        return switch (kind) {
            case INSERT -> new Operation.Insert(input);
            case UPSERT -> new Operation.Upsert(input);
            case UPDATE, DELETE -> throw new IllegalStateException(
                "record-backed DML carrier rejects DmlKind." + kind + " in its compact constructor");
        };
    }

    /**
     * Sealed common supertype of the four direct-return DML mutation variants. Carries the per-field
     * data the INSERT / UPDATE / DELETE / UPSERT emitters share: a pre-resolved
     * {@link DmlReturnExpression} arm that captures the entire return-shape dispatch (encoded ID,
     * projected {@code @table}, or class-backed payload). The {@code Projected*} (@table) arms
     * are legitimate only for INSERT / UPDATE / UPSERT, whose rows survive the statement and can be
     * read back by a follow-up SELECT; DELETE is excluded from them (the row is gone after the
     * statement, RETURNING carries only the primary key), and {@link MutationDeleteTableField}'s
     * compact constructor rejects a {@code Projected*} arm.
     *
     * <p>The input surface varies by verb. INSERT / UPSERT carry the {@code @table}
     * {@link ArgumentRef.InputTypeArg.TableInputArg} that drives the statement directly. UPDATE
     * (R246) and DELETE (R266) instead carry the slim {@link InputArgRef} arg surface plus their
     * walker-produced carrier ({@link UpdateRows} / {@link DeleteRows}) and implement
     * {@link UpdateRowsField} / {@link DeleteRowsField}: per R222, input fields have no semantics
     * independent of the consuming field, so the SET/WHERE partition lives on the carrier, not a
     * {@code TableInputArg}.
     *
     * <p>The classifier picks the {@link DmlReturnExpression} arm once; emitters pattern-match on
     * {@link #returnExpression()} with no {@code instanceof ScalarReturnType}, no
     * {@code wrapper().isList()} lookup, and no {@code Optional} unwrapping.
     */
    sealed interface DmlTableField extends MutationField
            permits MutationInsertTableField, MutationUpdateTableField,
                    MutationDeleteTableField, MutationUpsertTableField {
        DmlReturnExpression returnExpression();

        /**
         * R63: the verb's typed dialect constraint, set at construction. Never null. UPSERT carries
         * {@link DialectRequirement.RejectsFamily}({@code ORACLE}); bulk UPDATE carries
         * {@link DialectRequirement.RequiresFamily}({@code POSTGRES}); INSERT, DELETE, and single-row
         * UPDATE carry {@link DialectRequirement.None#INSTANCE}. The emitter renders the request-time
         * guard from this arm rather than a hand-built {@code CodeBlock}.
         */
        DialectRequirement dialectRequirement();

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
            TableRef table) {
        return switch (expr) {
            case DmlReturnExpression.EncodedSingle ignored -> new DomainReturnType.Plain(OutputField.STRING_CLASS);
            case DmlReturnExpression.EncodedList ignored   -> new DomainReturnType.Plain(OutputField.STRING_CLASS);
            case DmlReturnExpression.ProjectedSingle ignored -> new DomainReturnType.Record(table);
            case DmlReturnExpression.ProjectedList ignored   -> new DomainReturnType.Record(table);
            // R406: discriminated-interface return re-projects the shared table into a jOOQ Record
            // (carrying __discriminator__) exactly like the projected @table return.
            case DmlReturnExpression.DiscriminatedSingle ignored -> new DomainReturnType.Record(table);
            case DmlReturnExpression.DiscriminatedList ignored   -> new DomainReturnType.Record(table);
        };
    }

    record MutationInsertTableField(
        String parentTypeName,
        String name,
        SourceLocation location,
        DmlReturnExpression returnExpression,
        DialectRequirement dialectRequirement,
        ArgumentRef.InputTypeArg.TableInputArg tableInputArg,
        Optional<ErrorChannel> errorChannel
    ) implements DmlTableField {
        @Override public DomainReturnType domainReturnType() {
            return dmlDomainReturnType(returnExpression, tableInputArg.inputTable());
        }
    }

    /**
     * R246: the {@code @mutation(typeName: UPDATE)} field that returns its {@code @table} type
     * directly. Unlike its INSERT / DELETE / UPSERT siblings it carries no {@code TableInputArg};
     * its input semantics are dissolved into the walker-produced {@link UpdateRows} carrier plus
     * the slim {@link InputArgRef} arg surface (per R222: input fields have no semantics
     * independent of the consuming field). Both slots are non-Optional; the field is only
     * constructed when the FieldBuilder pre-checks and the {@code UpdateRowsWalker} both pass.
     */
    record MutationUpdateTableField(
        String parentTypeName,
        String name,
        SourceLocation location,
        DmlReturnExpression returnExpression,
        DialectRequirement dialectRequirement,
        InputArgRef inputArg,
        UpdateRows updateRows,
        Optional<ErrorChannel> errorChannel
    ) implements DmlTableField, UpdateRowsField {
        @Override public DomainReturnType domainReturnType() {
            return dmlDomainReturnType(returnExpression, inputArg.table());
        }
    }

    /**
     * R266 / R287: the {@code @mutation(typeName: DELETE)} field that returns an encoded ID. Unlike
     * its INSERT / UPDATE / UPSERT siblings, DELETE cannot return a projected {@code @table}: the row
     * is gone after the statement, and RETURNING carries only the primary key, so a full {@code @table}
     * projection is impossible. The {@code @mutation} classifier rejects DELETE -> {@code @table} at
     * authoring time (R287), so {@link #returnExpression} only ever holds an {@code Encoded*} arm; the
     * compact constructor backstops that invariant by rejecting a {@code Projected*} arm.
     *
     * <p>Like its UPDATE sibling {@link MutationUpdateTableField} (and unlike INSERT / UPSERT) it
     * carries no {@code TableInputArg}: its input semantics are dissolved into the
     * {@code DeleteRowsWalker}-produced {@link DeleteRows} carrier plus the slim {@link InputArgRef}
     * arg surface (per R222: input fields have no semantics independent of the consuming field).
     * DELETE's carrier has no SET partition — every admitted input column is a WHERE filter
     * ({@link DeleteRows#whereColumns()}) — and supports the {@code multiRow: true}
     * {@link DeleteRows.Broadcast} arm UPDATE rejects. The non-return slots are non-Optional; the
     * field is only constructed when the FieldBuilder pre-checks and the {@code DeleteRowsWalker}
     * both pass.
     *
     * <p>The name encodes the family axis (direct-return DML on a {@code @table}, as opposed to the
     * {@code *DmlRecordField} / {@code *PayloadField} carriers), not the return shape, which
     * {@link #returnExpression} carries.
     */
    record MutationDeleteTableField(
        String parentTypeName,
        String name,
        SourceLocation location,
        DmlReturnExpression returnExpression,
        DialectRequirement dialectRequirement,
        InputArgRef inputArg,
        DeleteRows deleteRows,
        Optional<ErrorChannel> errorChannel
    ) implements DmlTableField, DeleteRowsField {
        public MutationDeleteTableField {
            if (returnExpression instanceof DmlReturnExpression.ProjectedSingle
                    || returnExpression instanceof DmlReturnExpression.ProjectedList) {
                throw new IllegalArgumentException(
                    "MutationDeleteTableField cannot carry a projected @table return ("
                    + returnExpression.getClass().getSimpleName() + "): DELETE removes the row, and "
                    + "RETURNING carries only the primary key, so a full @table projection is "
                    + "impossible. The @mutation classifier rejects DELETE -> @table at authoring "
                    + "time (R287); this carrier only ever holds an encoded-ID return.");
            }
        }
        @Override public DomainReturnType domainReturnType() {
            return dmlDomainReturnType(returnExpression, inputArg.table());
        }
    }

    record MutationUpsertTableField(
        String parentTypeName,
        String name,
        SourceLocation location,
        DmlReturnExpression returnExpression,
        DialectRequirement dialectRequirement,
        ArgumentRef.InputTypeArg.TableInputArg tableInputArg,
        Optional<ErrorChannel> errorChannel
    ) implements DmlTableField {
        @Override public DomainReturnType domainReturnType() {
            return dmlDomainReturnType(returnExpression, tableInputArg.inputTable());
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
        ServiceMethodCall serviceMethodCall,
        Optional<ErrorChannel> errorChannel
    ) implements MutationField, ServiceField {
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
        ServiceMethodCall serviceMethodCall,
        Optional<ErrorChannel> errorChannel
    ) implements MutationField, ServiceField {
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
            return new DomainReturnType.TableRecord(OutputField.peelToClassName(serviceMethodCall.javaReturnType()));
        }
    }

    /**
     * A mutation field backed by a developer-provided service method that returns a multitable
     * {@link GraphitronType.InterfaceType} over distinct-table participants (R365, route (a)). The
     * mutation analogue of {@link QueryField.QueryServicePolymorphicField}: the service hands back a
     * PK-populated jOOQ {@code TableRecord} per branch, and the emitted fetcher dispatches on each
     * returned record's runtime class against the participant set, tags {@code __typename}, and
     * auto-fetches the selected columns by PK. Interface only — a union return is permanently
     * unsupported and a single-table discriminated interface is deferred, both rejected at classify.
     */
    record MutationServicePolymorphicField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef.PolymorphicReturnType returnType,
        List<ParticipantRef> participants,
        ServiceMethodCall serviceMethodCall,
        Optional<ErrorChannel> errorChannel
    ) implements MutationField, ServiceField {
        public MutationServicePolymorphicField {
            participants = List.copyOf(participants);
        }
        @Override public DomainReturnType domainReturnType() {
            return new DomainReturnType.Plain(OutputField.OBJECT_CLASS);
        }
    }

    /**
     * R405 — the mutation analogue of {@link QueryField.QueryServiceTableInterfaceField}: a root
     * {@code @service} mutation returning a single-table discriminated interface
     * ({@code @table @discriminate}). Single-table sibling of {@link MutationServicePolymorphicField}
     * (route (a)); the service hands back records of the one shared table, and the emitted fetcher
     * collects their PKs, runs one by-PK SELECT projecting {@code __discriminator__} plus the
     * participant field set and discriminator-gated cross-table {@code LEFT JOIN}s, and lets the
     * per-{@code TableInterfaceType} {@code TypeResolver} route each row off the live discriminator
     * value (rather than route (a)'s runtime-class dispatch, which cannot distinguish same-table
     * subtypes). Carries the read-side single-table discrimination data plus the service binding; the
     * payload is a raw {@code Record} / {@code List<Record>}.
     */
    record MutationServiceTableInterfaceField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef.TableBoundReturnType returnType,
        String discriminatorColumn,
        List<String> knownDiscriminatorValues,
        List<ParticipantRef> participants,
        ServiceMethodCall serviceMethodCall,
        Optional<ErrorChannel> errorChannel
    ) implements MutationField, ServiceField {
        public MutationServiceTableInterfaceField {
            knownDiscriminatorValues = List.copyOf(knownDiscriminatorValues);
            participants = List.copyOf(participants);
        }
        @Override public DomainReturnType domainReturnType() {
            return new DomainReturnType.Plain(OutputField.OBJECT_CLASS);
        }
    }

    /**
     * R75 / Phase 1 — a record-returning DML mutation: the schema field carries
     * {@code @mutation(typeName: INSERT|UPSERT)}, takes a {@code @table} input, and
     * returns a payload carrier (an SDL Object admitted by
     * {@code BuildContext.scanStructuralDmlPayload} as a single non-errors data field whose
     * element is an {@code @table}-bound type). Sibling to {@link DmlTableField}: the latter
     * covers the "direct @table return" shape ({@code createFilm: Film}), this covers the
     * "payload wrap" shape ({@code createFilm: CreateFilmPayload}). The carrier's data field is
     * classified as {@link ChildField.RecordTableField}.
     *
     * <p>The {@code kind} discriminator drives per-DML-kind emit variation (INSERT and UPSERT
     * have distinct SQL shapes); the model is one permit because the components are
     * identical across those kinds. {@code kind == UPDATE} is carved off onto
     * {@link MutationUpdatePayloadField} (R258) and {@code kind == DELETE} onto
     * {@link MutationDeletePayloadField} (R266), each sourcing its SET/WHERE partition from a
     * walker carrier ({@link UpdateRows} / {@link DeleteRows}) rather than {@code @value} /
     * {@code @lookupKey}; the compact-constructor invariant rejects both here. The live range is
     * {@code {INSERT, UPSERT}}.
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
            // R258: UPDATE is carved off onto MutationUpdatePayloadField — the payload-returning
            // UPDATE sources its SET/WHERE partition from the UpdateRows walker carrier (PK-or-UK
            // matched-key membership), not @value, so it no longer flows through this leaf.
            // R266: DELETE is carved off onto MutationDeletePayloadField — the payload-returning
            // DELETE sources its WHERE columns from the DeleteRows walker carrier, not @lookupKey /
            // PK-coverage on a TableInputArg. With both carved off, the live DmlKind range here is
            // {INSERT, UPSERT}; each carve-out monotonically shrinks the range the eventual
            // dml-record-carrier-sealed-on-kind split must carry.
            if (kind == DmlKind.UPDATE) {
                throw new IllegalArgumentException(
                    "MutationDmlRecordField cannot carry DmlKind.UPDATE — R258 routes the "
                    + "payload-returning UPDATE onto MutationUpdatePayloadField via the UpdateRows "
                    + "walker carrier; this leaf carries {INSERT, UPSERT}.");
            }
            if (kind == DmlKind.DELETE) {
                throw new IllegalArgumentException(
                    "MutationDmlRecordField cannot carry DmlKind.DELETE — R266 routes the "
                    + "payload-returning DELETE onto MutationDeletePayloadField via the DeleteRows "
                    + "walker carrier; this leaf carries {INSERT, UPSERT}.");
            }
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
     * carrier's data field is classified as {@link ChildField.RecordTableField} with
     * {@link no.sikt.graphitron.rewrite.model.SourceKey.Cardinality#MANY}.
     *
     * <p>The classifier admits exactly
     * {@code (tableInputArg.list() == true, dataField.wrapper().isList() == true,
     * kind == INSERT)} and pairs the input cardinality to the data field's element
     * type. (R258 carved bulk UPDATE off onto {@link MutationBulkUpdatePayloadField}; R266 carved
     * bulk DELETE off onto {@link MutationBulkDeletePayloadField}; UPSERT is deferred to R145.) The
     * data table / input table agreement is now structurally pinned by the
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
     * <p><b>Per-kind emit variation.</b> Only INSERT lives on this leaf today; future UPSERT lifts
     * at R145 add a second shape with ON CONFLICT semantics. The principles-aligned target is
     * sealed-on-kind permits mirroring {@link DmlTableField}, tracked at
     * {@code dml-record-carrier-sealed-on-kind} as the joint lift over both record-carrier leaves.
     * Until that lift lands, the {@link DmlKind} enum field encodes the per-emit-shape dispatch and
     * the parameterised emitter switches on it.
     *
     * <p><b>DELETE carved off (R266).</b> The payload-returning bulk DELETE now lands on
     * {@link MutationBulkDeletePayloadField}, sourcing its per-row WHERE columns from the
     * {@link DeleteRows} walker carrier rather than the {@code TableInputArg}'s
     * {@code @lookupKey} / PK-coverage bindings. The data-field carrier is
     * {@link ChildField.SingleRecordIdFieldFromReturning} (no follow-up SELECT — the row is gone,
     * the encoded PK is consumed directly off the RETURNING record); only the input-side WHERE
     * source moved to the carrier. The compact constructor here rejects DELETE.
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
            if (kind == DmlKind.UPSERT) {
                throw new IllegalArgumentException(
                    "MutationBulkDmlRecordField cannot carry DmlKind.UPSERT under R144's "
                    + "cardinality-safety regime — UPSERT is refused at the upstream "
                    + "MutationInputResolver and lifts via R145 "
                    + "(mutation-cardinality-safety-upsert).");
            }
            // R258: UPDATE is carved off onto MutationBulkUpdatePayloadField — the payload-returning
            // bulk UPDATE sources its per-row SET/WHERE partition from the UpdateRows walker carrier
            // (PK-or-UK matched-key membership), not @value.
            if (kind == DmlKind.UPDATE) {
                throw new IllegalArgumentException(
                    "MutationBulkDmlRecordField cannot carry DmlKind.UPDATE — R258 routes the "
                    + "payload-returning bulk UPDATE onto MutationBulkUpdatePayloadField via the "
                    + "UpdateRows walker carrier; this leaf carries {INSERT}.");
            }
            // R266: DELETE is carved off onto MutationBulkDeletePayloadField — the payload-returning
            // bulk DELETE sources its per-row WHERE columns from the DeleteRows walker carrier, not
            // a TableInputArg's @lookupKey / PK-coverage bindings. The live DmlKind range here is
            // {INSERT}.
            if (kind == DmlKind.DELETE) {
                throw new IllegalArgumentException(
                    "MutationBulkDmlRecordField cannot carry DmlKind.DELETE — R266 routes the "
                    + "payload-returning bulk DELETE onto MutationBulkDeletePayloadField via the "
                    + "DeleteRows walker carrier; this leaf carries {INSERT}.");
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

    /**
     * R258 — the payload-returning {@code @mutation(typeName: UPDATE)} field with single
     * {@code @table} input (e.g. {@code updateFilmPayload(in: FilmUpdateInput!): FilmPayload}).
     * Sibling on two axes: of {@link MutationUpdateTableField} (the direct-{@code @table}/ID-return
     * UPDATE leaf) it shares the walker-driven input semantics — the slim {@link InputArgRef} arg
     * surface plus the {@link UpdateRows} carrier, with no {@code TableInputArg} and no {@code @value}
     * dependency; of {@link MutationDmlRecordField} it shares the structural-payload emit shape (a
     * plain SDL Object wrapping one {@code @table}-element data field classified as
     * {@link ChildField.RecordTableField}, emitted as a two-step PK-only {@code RETURNING}
     * inside {@code transactionResult} followed by the data field's response SELECT).
     *
     * <p>R246 migrated the direct-return UPDATE off {@code resolveInput}'s {@code @value}-partition
     * onto the {@code UpdateRowsWalker}'s PK-or-UK matched-key membership; this leaf does the same
     * for the payload-return shape, so no UPDATE path reads {@code @value} (the precondition for
     * R188 retiring the directive). Both slots are non-Optional: the field is only constructed when
     * the FieldBuilder pre-checks and the walker both pass; a walker {@code Err} surfaces as an
     * {@link no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField} with no carrier.
     * No {@link DmlKind} slot — the leaf identity is the kind.
     */
    record MutationUpdatePayloadField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef.ResultReturnType returnType,
        InputArgRef inputArg,
        UpdateRows updateRows,
        Optional<ErrorChannel> errorChannel
    ) implements MutationField, UpdateRowsField {
        @Override public DomainReturnType domainReturnType() {
            return new DomainReturnType.Record(inputArg.table());
        }
    }

    /**
     * R258 — the payload-returning {@code @mutation(typeName: UPDATE)} field with bulk
     * {@code @table} input and a list-shaped {@code @table}-element data field on the carrier
     * (e.g. {@code updateFilmsPayload(in: [FilmUpdateInput!]!): FilmsPayload}). Bulk sibling of
     * {@link MutationUpdatePayloadField}, exactly as {@link MutationBulkDmlRecordField} is the bulk
     * sibling of {@link MutationDmlRecordField}.
     *
     * <p>Emit follows the bulk record-carrier skeleton: per-row UPDATE inside one
     * {@code dsl.transactionResult(...)}, collecting PK echoes into a {@code Result<RecordN<PK>>} in
     * input order so the data field's {@link ChildField.RecordTableField}
     * ({@link no.sikt.graphitron.rewrite.model.SourceKey.Cardinality#MANY}) fetcher renders rows in
     * input order. The per-row SET/WHERE partition is sourced from the {@link UpdateRows} carrier
     * (PK-or-UK matched-key membership) rather than {@code @value}; see
     * {@link no.sikt.graphitron.rewrite.generators.TypeFetcherGenerator} for the emit path and the
     * order-preservation invariant {@code DmlBulkMutationsExecutionTest} pins at runtime.
     *
     * @see no.sikt.graphitron.rewrite.generators.TypeFetcherGenerator
     */
    record MutationBulkUpdatePayloadField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef.ResultReturnType returnType,
        InputArgRef inputArg,
        UpdateRows updateRows,
        Optional<ErrorChannel> errorChannel
    ) implements MutationField, UpdateRowsField {
        @Override public DomainReturnType domainReturnType() {
            return new DomainReturnType.Record(inputArg.table());
        }
    }

    /**
     * R266 — the payload-returning {@code @mutation(typeName: DELETE)} field with single
     * {@code @table} input (e.g. {@code deleteFilmPayload(in: FilmDeleteInput!): FilmPayload}).
     * The DELETE analogue of {@link MutationUpdatePayloadField}: of {@link MutationDeleteTableField}
     * (the direct-{@code @table}/ID-return DELETE leaf) it shares the walker-driven input semantics
     * — the slim {@link InputArgRef} arg surface plus the {@link DeleteRows} carrier, with no
     * {@code TableInputArg}; of {@link MutationDmlRecordField} it shares the structural-payload emit
     * shape (a plain SDL Object wrapping one {@code @table}-element or ID-scalar data field, emitted
     * as a two-step PK-only {@code RETURNING} inside {@code transactionResult} — no follow-up SELECT
     * after the row is gone).
     *
     * <p>R266 migrated the payload DELETE off {@code resolveInput}'s {@code @lookupKey} / PK-coverage
     * WHERE source onto the {@code DeleteRowsWalker}'s PK-or-UK identification; carving DELETE off
     * {@code resolveInput} retires the last live {@code @value} consumer. Both slots are non-Optional:
     * the field is only constructed when the FieldBuilder pre-checks and the walker both pass; a
     * walker {@code Err} surfaces as an
     * {@link no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField} with no carrier.
     * No {@link DmlKind} slot — the leaf identity is the kind.
     */
    record MutationDeletePayloadField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef.ResultReturnType returnType,
        InputArgRef inputArg,
        DeleteRows deleteRows,
        Optional<ErrorChannel> errorChannel
    ) implements MutationField, DeleteRowsField {
        @Override public DomainReturnType domainReturnType() {
            return new DomainReturnType.Record(inputArg.table());
        }
    }

    /**
     * R266 — the payload-returning {@code @mutation(typeName: DELETE)} field with bulk
     * {@code @table} input and a list-shaped data field on the carrier
     * (e.g. {@code deleteFilmsPayload(in: [FilmDeleteInput!]!): FilmsPayload}). Bulk sibling of
     * {@link MutationDeletePayloadField}, exactly as {@link MutationBulkUpdatePayloadField} is the
     * bulk sibling of {@link MutationUpdatePayloadField}.
     *
     * <p>Emit follows the bulk record-carrier skeleton: per-row DELETE inside one
     * {@code dsl.transactionResult(...)}, collecting PK echoes into a {@code Result<RecordN<PK>>} in
     * input order so the data field's {@link ChildField.RecordTableField}
     * ({@link no.sikt.graphitron.rewrite.model.SourceKey.Cardinality#MANY}) fetcher renders rows in
     * input order. The per-row WHERE columns are sourced from the {@link DeleteRows} carrier rather
     * than {@code tableInputArg.fieldBindings()}; see
     * {@link no.sikt.graphitron.rewrite.generators.TypeFetcherGenerator} for the emit path and the
     * order-preservation invariant {@code DmlBulkMutationsExecutionTest} pins at runtime.
     *
     * @see no.sikt.graphitron.rewrite.generators.TypeFetcherGenerator
     */
    record MutationBulkDeletePayloadField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef.ResultReturnType returnType,
        InputArgRef inputArg,
        DeleteRows deleteRows,
        Optional<ErrorChannel> errorChannel
    ) implements MutationField, DeleteRowsField {
        @Override public DomainReturnType domainReturnType() {
            return new DomainReturnType.Record(inputArg.table());
        }
    }
}
