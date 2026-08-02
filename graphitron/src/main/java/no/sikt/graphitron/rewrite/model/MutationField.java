package no.sikt.graphitron.rewrite.model;

import graphql.language.SourceLocation;
import no.sikt.graphitron.rewrite.ArgumentRef;

import java.util.List;
import java.util.Optional;

/**
 * A field on the {@code Mutation} type. The only fields permitted to write to the database.
 *
 * <p>Every variant is fetcher-emitting and implements {@link WithErrorChannel}: a populated
 * channel makes the emitter wrap the fetcher body in a try/catch routing thrown exceptions
 * into the typed {@code errors} field. The channel is resolved by
 * {@code FieldBuilder.resolveErrorChannel}; an {@link Optional#empty()} channel means no
 * {@code errors} field on the payload, not an unclassified one.
 */
public sealed interface MutationField extends RootField, WithErrorChannel
    permits MutationField.DmlTableField, MutationField.MutationRoutineWriteField,
            MutationField.MutationServiceTableField,
            MutationField.MutationServiceRecordField, MutationField.MutationServicePolymorphicField,
            MutationField.MutationServiceTableInterfaceField,
            MutationField.MutationDmlRecordField,
            MutationField.MutationBulkDmlRecordField,
            MutationField.MutationUpdatePayloadField, MutationField.MutationBulkUpdatePayloadField,
            MutationField.MutationDeletePayloadField, MutationField.MutationBulkDeletePayloadField {

    /** The root is the empty product; {@code parentArrival} is ignored. */
    @Override default Source source(Arrival parentArrival) { return new Source.Root.Mutation(); }

    @Override default Target target() {
        return switch (this) {
            // The return-shape slot (DmlReturnExpression) encodes both wrapper and shape: Column
            // (encoded ID) vs Table (in-fetcher follow-up SELECT). The follow-up itself is the derived
            // re-fetch, not a tuple axis.
            case MutationInsertTableField f -> OutputField.dmlTarget(f.returnExpression());
            case MutationUpdateTableField f -> OutputField.dmlTarget(f.returnExpression());
            case MutationDeleteTableField f -> OutputField.dmlTarget(f.returnExpression());
            case MutationUpsertTableField f -> OutputField.dmlTarget(f.returnExpression());
            // Routine write: the response is the post-commit chain re-read projecting the
            // terminus @table type, a bare Table shape exactly as the read chain projects.
            case MutationRoutineWriteField f -> OutputField.wrap(f.returnType().wrapper(), new TargetShape.Table());
            case MutationServiceTableField f -> OutputField.wrap(f.returnType().wrapper(), new TargetShape.Table());
            case MutationServiceRecordField f -> OutputField.listOrSingle(f.returnType().wrapper(), new TargetShape.Record());
            // Interface-only service-polymorphic return (union/table-interface rejected at classify).
            case MutationServicePolymorphicField f -> OutputField.wrap(f.returnType().wrapper(), new TargetShape.Interface());
            // Single-table service interface return: raw Record / List<Record> routed by the
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
     * Sealed common supertype of the four direct-return DML mutation variants. The pre-resolved
     * {@link DmlReturnExpression} arm captures the entire return-shape dispatch (encoded ID,
     * projected {@code @table}, or class-backed payload); the classifier picks it once and
     * emitters pattern-match on {@link #returnExpression()}. {@code Projected*} (@table) arms are
     * legitimate only for INSERT / UPDATE / UPSERT, whose rows survive the statement and can be
     * read back by a follow-up SELECT; DELETE is excluded (the row is gone, RETURNING carries only
     * the primary key), and {@link MutationDeleteTableField}'s compact constructor rejects a
     * {@code Projected*} arm.
     *
     * <p>The input surface varies by verb. INSERT / UPSERT carry the {@code @table}
     * {@link ArgumentRef.InputTypeArg.TableInputArg} that drives the statement directly. UPDATE
     * and DELETE instead carry the slim {@link InputArgRef} arg surface plus their
     * walker-produced carrier ({@link UpdateRows} / {@link DeleteRows}) and implement
     * {@link UpdateRowsField} / {@link DeleteRowsField}: input fields have no semantics
     * independent of the consuming field, so the SET/WHERE partition lives on the carrier, not a
     * {@code TableInputArg}.
     */
    sealed interface DmlTableField extends MutationField
            permits MutationInsertTableField, MutationUpdateTableField,
                    MutationDeleteTableField, MutationUpsertTableField {
        DmlReturnExpression returnExpression();

        /**
         * The verb's typed dialect constraint, set at construction. Never null. UPSERT carries
         * {@link DialectRequirement.RejectsFamily}({@code ORACLE}); bulk UPDATE carries
         * {@link DialectRequirement.RequiresFamily}({@code POSTGRES}); INSERT, DELETE, and single-row
         * UPDATE carry {@link DialectRequirement.None#INSTANCE}. The emitter renders the request-time
         * guard from this arm.
         */
        DialectRequirement dialectRequirement();

        SourceLocation location();

        String name();
    }

    /**
     * {@code Encoded*} arms (ID-return) emit an encoded {@code String} at {@code env.getSource()};
     * {@code Projected*} / {@code Discriminated*} arms emit a sparse {@code RecordN<...>}
     * projection on the table's PK columns. Consumed at the validator's group-by step, where DML
     * siblings reaching the same SDL ID return type must agree with the column-encoded NodeId
     * producers also returning ID.
     */
    private static DomainReturnType dmlDomainReturnType(
            DmlReturnExpression expr,
            TableRef table) {
        return switch (expr) {
            case DmlReturnExpression.EncodedSingle ignored -> new DomainReturnType.Plain(OutputField.STRING_CLASS);
            case DmlReturnExpression.EncodedList ignored   -> new DomainReturnType.Plain(OutputField.STRING_CLASS);
            case DmlReturnExpression.ProjectedSingle ignored -> new DomainReturnType.Record(table);
            case DmlReturnExpression.ProjectedList ignored   -> new DomainReturnType.Record(table);
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
        Optional<ErrorChannel.RouterDispatched> errorChannel
    ) implements DmlTableField {
        @Override public DomainReturnType domainReturnType() {
            return dmlDomainReturnType(returnExpression, tableInputArg.inputTable());
        }
    }

    /**
     * The {@code @mutation(typeName: UPDATE)} field that returns its {@code @table} type
     * directly. Unlike its INSERT / UPSERT siblings it carries no {@code TableInputArg}; its input
     * semantics live on the walker-produced {@link UpdateRows} carrier plus the slim
     * {@link InputArgRef} arg surface. Both slots are non-Optional; the field is only constructed
     * when the FieldBuilder pre-checks and the {@code UpdateRowsWalker} both pass.
     */
    record MutationUpdateTableField(
        String parentTypeName,
        String name,
        SourceLocation location,
        DmlReturnExpression returnExpression,
        DialectRequirement dialectRequirement,
        InputArgRef inputArg,
        UpdateRows updateRows,
        Optional<ErrorChannel.RouterDispatched> errorChannel
    ) implements DmlTableField, UpdateRowsField {
        @Override public DomainReturnType domainReturnType() {
            return dmlDomainReturnType(returnExpression, inputArg.table());
        }
    }

    /**
     * The {@code @mutation(typeName: DELETE)} field that returns an encoded ID. Unlike its
     * INSERT / UPDATE / UPSERT siblings, DELETE cannot return a projected {@code @table}: the row
     * is gone after the statement and RETURNING carries only the primary key. The {@code @mutation}
     * classifier rejects DELETE -> {@code @table} at authoring time, so {@link #returnExpression}
     * only ever holds an {@code Encoded*} arm; the compact constructor backstops that invariant.
     *
     * <p>Like its UPDATE sibling {@link MutationUpdateTableField} it carries no
     * {@code TableInputArg}: its input semantics live on the {@code DeleteRowsWalker}-produced
     * {@link DeleteRows} carrier plus the slim {@link InputArgRef} arg surface. DELETE's carrier
     * has no SET partition; every admitted input column is a WHERE filter
     * ({@link DeleteRows#whereColumns()}), and it supports the {@code multiRow: true}
     * {@link DeleteRows.Broadcast} arm UPDATE rejects. The non-return slots are non-Optional; the
     * field is only constructed when the FieldBuilder pre-checks and the {@code DeleteRowsWalker}
     * both pass.
     *
     * <p>The name encodes the family axis (direct-return DML on a {@code @table}, as opposed to
     * the {@code *DmlRecordField} / {@code *PayloadField} carriers), not the return shape.
     */
    record MutationDeleteTableField(
        String parentTypeName,
        String name,
        SourceLocation location,
        DmlReturnExpression returnExpression,
        DialectRequirement dialectRequirement,
        InputArgRef inputArg,
        DeleteRows deleteRows,
        Optional<ErrorChannel.RouterDispatched> errorChannel
    ) implements DmlTableField, DeleteRowsField {
        public MutationDeleteTableField {
            if (returnExpression instanceof DmlReturnExpression.ProjectedSingle
                    || returnExpression instanceof DmlReturnExpression.ProjectedList) {
                throw new IllegalArgumentException(
                    "MutationDeleteTableField cannot carry a projected @table return ("
                    + returnExpression.getClass().getSimpleName() + "): DELETE removes the row, and "
                    + "RETURNING carries only the primary key, so a full @table projection is "
                    + "impossible. The @mutation classifier rejects DELETE -> @table at authoring "
                    + "time; this carrier only ever holds an encoded-ID return.");
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
        Optional<ErrorChannel.RouterDispatched> errorChannel
    ) implements DmlTableField {
        @Override public DomainReturnType domainReturnType() {
            return dmlDomainReturnType(returnExpression, tableInputArg.inputTable());
        }
    }

    /**
     * A mutation field whose table chain starts with a database routine: the routine call
     * <em>is</em> the write, and it commits before the follow-up query runs. The emitted fetcher
     * is the DML two-step transposed onto the chain. Step 1 executes the routine inside the
     * per-field {@code dsl.transactionResult(...)} boundary and captures only the columns hop 0's
     * key needs from the routine's result rows (the analog of DML's PK-only {@code RETURNING}).
     * Step 2 runs after the commit: a read-only SELECT anchored on hop 0's table with the captured
     * keys, remaining hops joined as the read chain joins them, projecting the terminus
     * {@code @table} type. The routine never appears in step 2's {@code FROM}: re-invoking it
     * would re-execute the write, so the field's return binds to the re-read only.
     *
     * <p>The chain shape is the shared {@link RoutineChain} (the {@code QueryRoutineTableField}
     * carrier), exposed through {@link RoutineChainField}; this leaf adds one pin of its own,
     * {@code hops} non-empty. With no hop there is no post-commit table to re-read from; the
     * single-node Mutation {@code @routine} classifies as a typed {@code Deferred}.
     *
     * <p>{@code errorChannel()} is pinned empty: the return is the direct terminus {@code @table}
     * type (the terminus rule), never a payload carrying a typed {@code errors} field, so the
     * fetcher wraps in the no-channel redacting catch arm. An SQL error from the routine rolls
     * the transaction back at the {@code transactionResult} boundary and surfaces exactly as DML
     * errors do.
     */
    record MutationRoutineWriteField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef.TableBoundReturnType returnType,
        RoutineChain chain
    ) implements MutationField, RoutineChainField {

        public MutationRoutineWriteField {
            if (chain == null) {
                throw new NullPointerException("MutationRoutineWriteField.chain must not be null");
            }
            // At least one hop, so a post-commit re-read anchor exists. The classifier routes
            // the single-node shape to the typed Deferred before construction.
            if (chain.hops().isEmpty()) {
                throw new IllegalArgumentException(
                    "MutationRoutineWriteField requires at least one @reference hop: with no hop "
                    + "there is no post-commit table to re-read from, and the single-node Mutation "
                    + "@routine classifies as typed Deferred (routine-write-result-shapes)");
            }
            // Terminus invariant: the projected @table type is the chain's last node.
            if (!chain.terminus().denotesSameTableAs(returnType.table())) {
                throw new IllegalArgumentException(
                    "MutationRoutineWriteField terminus mismatch: the chain ends on '"
                    + chain.terminus().tableName() + "' but the field's @table type is bound to '"
                    + returnType.table().tableName() + "'; the classifier's terminus rule must "
                    + "reject this before construction");
            }
        }

        @Override public Optional<ErrorChannel.RouterDispatched> errorChannel() {
            return Optional.empty();
        }

        @Override public DomainReturnType domainReturnType() {
            return new DomainReturnType.Record(returnType.table());
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
     *
     * <p><b>Reentry realization.</b> Value-level re-fetch without a site-level re-query,
     * exactly as {@link QueryField.QueryServiceTableField}: {@code requiresReFetch()} is true,
     * {@code emitsKeyedReQuery()} is false, and the re-projection is realized by the downstream
     * child fetchers' {@code $project}. See that leaf's javadoc for the fact linkage.
     */
    record MutationServiceTableField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef.TableBoundReturnType returnType,
        ServiceMethodCall serviceMethodCall,
        Optional<ErrorChannel.Mapped> errorChannel
    ) implements MutationField, ServiceField {
        /**
         * See {@link ChildField.ServiceTableField#domainReturnType()}: the typed {@code XRecord}
         * is consumer-equivalent to a {@code Record(table)} via subtyping.
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
        Optional<ErrorChannel.Mapped> errorChannel
    ) implements MutationField, ServiceField {
        /**
         * The carrier-shape case ({@code @service} mutation whose reflected return-element is the
         * payload's single {@code @table}-typed data field's record class) puts a typed
         * {@code XRecord} verbatim at {@code env.getSource()}: the arm is
         * {@link DomainReturnType.TableRecord}, its {@link no.sikt.graphitron.javapoet.ClassName}
         * peeled from {@link MethodRef#returnType()}'s parameterised shape. Non-carrier service
         * shapes also answer {@code TableRecord}: the validator's structural equality groups
         * producers by SDL return type, and only the carrier-shape conflict against
         * {@link MutationDmlRecordField} / {@link MutationBulkDmlRecordField}'s
         * {@link DomainReturnType.Record} arm is surfaced; other arrangements either agree or are
         * filtered by upstream classifier rejections.
         */
        @Override public DomainReturnType domainReturnType() {
            return new DomainReturnType.TableRecord(OutputField.peelToClassName(serviceMethodCall.javaReturnType()));
        }
    }

    /**
     * A mutation field backed by a developer-provided service method that returns a multitable
     * {@link GraphitronType.InterfaceType} over distinct-table participants (route (a)). The
     * mutation analogue of {@link QueryField.QueryServicePolymorphicField}: the service hands back a
     * PK-populated jOOQ {@code TableRecord} per branch, and the emitted fetcher dispatches on each
     * returned record's runtime class against the participant set, tags {@code __typename}, and
     * auto-fetches the selected columns by PK. Interface only, and a distinct-table interface:
     * a union return is permanently unsupported (rejected at classify), and a single-table
     * discriminated interface routes to the sibling {@link MutationServiceTableInterfaceField}
     * leaf, not this one.
     */
    record MutationServicePolymorphicField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef.PolymorphicReturnType returnType,
        List<ParticipantRef> participants,
        ServiceMethodCall serviceMethodCall,
        Optional<ErrorChannel.Mapped> errorChannel
    ) implements MutationField, ServiceField {
        public MutationServicePolymorphicField {
            participants = List.copyOf(participants);
        }
        @Override public DomainReturnType domainReturnType() {
            return new DomainReturnType.Plain(OutputField.OBJECT_CLASS);
        }
    }

    /**
     * The mutation analogue of {@link QueryField.QueryServiceTableInterfaceField}: a root
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
        Optional<ErrorChannel.Mapped> errorChannel
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
     * A record-returning DML mutation: the schema field carries
     * {@code @mutation(typeName: INSERT|UPSERT)}, takes a DML input, and
     * returns a payload carrier (an SDL Object admitted by
     * {@code BuildContext.scanStructuralDmlPayload} as a single non-errors data field whose
     * element is an {@code @table}-bound type). Sibling to {@link DmlTableField}: the latter
     * covers the "direct @table return" shape ({@code createFilm: Film}), this covers the
     * "payload wrap" shape ({@code createFilm: CreateFilmPayload}). The carrier's data field is
     * classified as a record-sourced {@link ChildField.BatchedTableField}.
     *
     * <p>The {@code kind} discriminator drives per-DML-kind emit variation (INSERT and UPSERT
     * have distinct SQL shapes); the model is one permit because the components are identical
     * across those kinds. The payload-returning UPDATE lives on {@link MutationUpdatePayloadField}
     * and DELETE on {@link MutationDeletePayloadField}, each sourcing its SET/WHERE partition from
     * a walker carrier ({@link UpdateRows} / {@link DeleteRows}); the compact constructor rejects
     * both here, so the live range is {@code {INSERT, UPSERT}}.
     *
     * <p>{@link #returnType()} is the carrier's {@link ReturnTypeRef.ResultReturnType} with no
     * unwrap: the SDL's structural truth. {@link #tableInputArg()} carries the input
     * {@code @table} exactly like the {@link DmlTableField} permits; the emitter reads
     * {@code tableInputArg.inputTable().primaryKeyColumns()} for the PK-only {@code RETURNING}
     * clause of the two-step DML.
     */
    record MutationDmlRecordField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef.ResultReturnType returnType,
        ArgumentRef.InputTypeArg.TableInputArg tableInputArg,
        DmlKind kind,
        Optional<ErrorChannel.RouterDispatched> errorChannel
    ) implements MutationField {

        public MutationDmlRecordField {
            if (kind == DmlKind.UPDATE) {
                throw new IllegalArgumentException(
                    "MutationDmlRecordField cannot carry DmlKind.UPDATE — the UpdateRows walker "
                    + "routes the payload-returning UPDATE onto MutationUpdatePayloadField; this "
                    + "leaf carries {INSERT, UPSERT}.");
            }
            if (kind == DmlKind.DELETE) {
                throw new IllegalArgumentException(
                    "MutationDmlRecordField cannot carry DmlKind.DELETE — the DeleteRows walker "
                    + "routes the payload-returning DELETE onto MutationDeletePayloadField; this "
                    + "leaf carries {INSERT, UPSERT}.");
            }
        }
        @Override public DomainReturnType domainReturnType() {
            return new DomainReturnType.Record(tableInputArg.inputTable());
        }
    }

    /**
     * A record-returning DML mutation with bulk DML input and a list-shaped
     * {@code @table}-element data field on the carrier. The carrier itself is single
     * ({@code FilmsPayload}, not {@code [FilmsPayload!]!}); the list lives on the data field
     * ({@code films: [Film!]}). Sibling to {@link MutationDmlRecordField}, which covers the
     * singleton-data-field case with single input. The carrier's data field is classified as a
     * record-sourced {@link ChildField.BatchedTableField} with a many-arity source.
     *
     * <p>The classifier admits exactly
     * {@code (tableInputArg.list() == true, dataField.wrapper().isList() == true,
     * kind == INSERT)} and pairs the input cardinality to the data field's element type. The
     * payload-returning bulk UPDATE lives on {@link MutationBulkUpdatePayloadField} and bulk
     * DELETE on {@link MutationBulkDeletePayloadField}; UPSERT is structurally compatible with
     * this leaf but refused upstream by {@code MutationInputResolver} under the
     * cardinality-safety regime. The data table / input table agreement is structurally pinned by
     * the {@link ProducerBinding.DmlEmitted} compact constructor's
     * {@code reflectedClass.getName().equals(tableRef.recordClass().reflectionName())}
     * invariant, surfaced via {@link Rejection.AuthorError.RecordBindingMultiProducer} when
     * disagreeing producers fold against the same SDL payload type.
     *
     * <p><b>Order preservation invariant.</b> {@code output.data[i]} corresponds to
     * {@code input[i]} for all {@code i ∈ [0, N)}. The emitter satisfies this via batched per-row
     * DML inside one transaction (N+1 statements), collecting PKs in input order into a
     * {@code Result<RecordN<PK>>}; the downstream data-field fetcher
     * ({@link no.sikt.graphitron.rewrite.generators.FetcherEmitter}'s
     * {@code buildSingleRecordTableFetcherValue} {@link Arity#MANY} arm) builds a PK-keyed map of
     * the response-SELECT result and iterates the input-ordered PK list. Input order is therefore
     * a property of the emitted Java code, not of the SQL planner's scan strategy for
     * {@code WHERE pk IN (...)}. The contract is a runtime claim with no compile-time signal;
     * its audit is {@code DmlBulkMutationsExecutionTest}'s N=3 deliberately-non-PK-ordered
     * round-trip, and the emit path is
     * {@link no.sikt.graphitron.rewrite.generators.TypeFetcherGenerator}'s
     * {@code buildMutationBulkDmlRecordFetcher}.
     *
     * <p>The {@link DmlKind} field encodes the per-emit-shape dispatch and the parameterised
     * emitter switches on it; the live range here is {@code {INSERT}}.
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
        Optional<ErrorChannel.RouterDispatched> errorChannel
    ) implements MutationField {

        public MutationBulkDmlRecordField {
            if (kind == DmlKind.UPSERT) {
                throw new IllegalArgumentException(
                    "MutationBulkDmlRecordField cannot carry DmlKind.UPSERT under the "
                    + "cardinality-safety regime — UPSERT is refused at the upstream "
                    + "MutationInputResolver pending a designed cardinality story.");
            }
            if (kind == DmlKind.UPDATE) {
                throw new IllegalArgumentException(
                    "MutationBulkDmlRecordField cannot carry DmlKind.UPDATE — the UpdateRows walker "
                    + "routes the payload-returning bulk UPDATE onto MutationBulkUpdatePayloadField; "
                    + "this leaf carries {INSERT}.");
            }
            if (kind == DmlKind.DELETE) {
                throw new IllegalArgumentException(
                    "MutationBulkDmlRecordField cannot carry DmlKind.DELETE — the DeleteRows walker "
                    + "routes the payload-returning bulk DELETE onto MutationBulkDeletePayloadField; "
                    + "this leaf carries {INSERT}.");
            }
            if (!tableInputArg.list()) {
                throw new IllegalArgumentException(
                    "MutationBulkDmlRecordField requires a bulk (list) input "
                    + "(tableInputArg.list() == true); single-input belongs on "
                    + "MutationDmlRecordField.");
            }
        }
        @Override public DomainReturnType domainReturnType() {
            return new DomainReturnType.Record(tableInputArg.inputTable());
        }
    }

    /**
     * The payload-returning {@code @mutation(typeName: UPDATE)} field with single
     * DML input (e.g. {@code updateFilmPayload(in: FilmUpdateInput!): FilmPayload}).
     * Sibling on two axes: of {@link MutationUpdateTableField} (the direct-{@code @table}/ID-return
     * UPDATE leaf) it shares the walker-driven input semantics, the slim {@link InputArgRef} arg
     * surface plus the {@link UpdateRows} carrier with no {@code TableInputArg}; of
     * {@link MutationDmlRecordField} it shares the structural-payload emit shape (a plain SDL
     * Object wrapping one {@code @table}-element data field classified as a record-sourced
     * {@link ChildField.BatchedTableField}, emitted as a two-step PK-only {@code RETURNING}
     * inside {@code transactionResult} followed by the data field's response SELECT).
     *
     * <p>The SET/WHERE partition comes from the {@code UpdateRowsWalker}'s PK-or-UK matched-key
     * membership. Both slots are non-Optional: the field is only constructed when the
     * FieldBuilder pre-checks and the walker both pass; a walker {@code Err} surfaces as an
     * {@link no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField} with no carrier.
     * No {@link DmlKind} slot; the leaf identity is the kind.
     */
    record MutationUpdatePayloadField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef.ResultReturnType returnType,
        InputArgRef inputArg,
        UpdateRows updateRows,
        Optional<ErrorChannel.RouterDispatched> errorChannel
    ) implements MutationField, UpdateRowsField {
        @Override public DomainReturnType domainReturnType() {
            return new DomainReturnType.Record(inputArg.table());
        }
    }

    /**
     * The payload-returning {@code @mutation(typeName: UPDATE)} field with bulk
     * DML input and a list-shaped {@code @table}-element data field on the carrier
     * (e.g. {@code updateFilmsPayload(in: [FilmUpdateInput!]!): FilmsPayload}). Bulk sibling of
     * {@link MutationUpdatePayloadField}, exactly as {@link MutationBulkDmlRecordField} is the bulk
     * sibling of {@link MutationDmlRecordField}.
     *
     * <p>Emit follows the bulk record-carrier skeleton: per-row UPDATE inside one
     * {@code dsl.transactionResult(...)}, collecting PK echoes into a {@code Result<RecordN<PK>>}
     * in input order so the data field's record-sourced {@link ChildField.BatchedTableField}
     * (many-arity) fetcher renders rows in input order. The per-row SET/WHERE partition is sourced
     * from the {@link UpdateRows} carrier (PK-or-UK matched-key membership); see
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
        Optional<ErrorChannel.RouterDispatched> errorChannel
    ) implements MutationField, UpdateRowsField {
        @Override public DomainReturnType domainReturnType() {
            return new DomainReturnType.Record(inputArg.table());
        }
    }

    /**
     * The payload-returning {@code @mutation(typeName: DELETE)} field with single
     * DML input (e.g. {@code deleteFilmPayload(in: FilmDeleteInput!): FilmPayload}).
     * The DELETE analogue of {@link MutationUpdatePayloadField}: of {@link MutationDeleteTableField}
     * (the direct-{@code @table}/ID-return DELETE leaf) it shares the walker-driven input
     * semantics, the slim {@link InputArgRef} arg surface plus the {@link DeleteRows} carrier with
     * no {@code TableInputArg}; of {@link MutationDmlRecordField} it shares the structural-payload
     * emit shape (a plain SDL Object wrapping one {@code @table}-element or ID-scalar data field,
     * emitted as a two-step PK-only {@code RETURNING} inside {@code transactionResult}, with no
     * follow-up SELECT after the row is gone).
     *
     * <p>The WHERE source is the {@code DeleteRowsWalker}'s PK-or-UK identification. Both slots
     * are non-Optional: the field is only constructed when the FieldBuilder pre-checks and the
     * walker both pass; a walker {@code Err} surfaces as an
     * {@link no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField} with no carrier.
     * No {@link DmlKind} slot; the leaf identity is the kind.
     */
    record MutationDeletePayloadField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef.ResultReturnType returnType,
        InputArgRef inputArg,
        DeleteRows deleteRows,
        Optional<ErrorChannel.RouterDispatched> errorChannel
    ) implements MutationField, DeleteRowsField {
        @Override public DomainReturnType domainReturnType() {
            return new DomainReturnType.Record(inputArg.table());
        }
    }

    /**
     * The payload-returning {@code @mutation(typeName: DELETE)} field with bulk
     * DML input and a list-shaped data field on the carrier
     * (e.g. {@code deleteFilmsPayload(in: [FilmDeleteInput!]!): FilmsPayload}). Bulk sibling of
     * {@link MutationDeletePayloadField}, exactly as {@link MutationBulkUpdatePayloadField} is the
     * bulk sibling of {@link MutationUpdatePayloadField}.
     *
     * <p>Emit follows the bulk record-carrier skeleton: per-row DELETE inside one
     * {@code dsl.transactionResult(...)}, collecting PK echoes into a {@code Result<RecordN<PK>>}
     * in input order so the data field's record-sourced {@link ChildField.BatchedTableField}
     * (many-arity) fetcher renders rows in input order. The per-row WHERE columns are sourced
     * from the {@link DeleteRows} carrier; see
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
        Optional<ErrorChannel.RouterDispatched> errorChannel
    ) implements MutationField, DeleteRowsField {
        @Override public DomainReturnType domainReturnType() {
            return new DomainReturnType.Record(inputArg.table());
        }
    }
}
