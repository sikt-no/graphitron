package no.sikt.graphitron.rewrite.model;

import graphql.language.SourceLocation;
import no.sikt.graphitron.rewrite.ArgumentRef;

import java.util.List;
import java.util.Objects;
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
            MutationField.MutationBulkDmlRecordField {

    /** The root is the empty product; {@code parentArrival} is ignored. */
    @Override default Source source(Arrival parentArrival) { return new Source.Root.Mutation(); }

    @Override default Target target() {
        return switch (this) {
            // The return-shape slot (DmlReturnExpression) encodes both wrapper and shape: Column
            // (encoded ID) vs Table (in-fetcher follow-up SELECT). The follow-up itself is the derived
            // re-fetch, not a tuple axis.
            case DmlTableField f -> OutputField.dmlTarget(f.returnExpression());
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
        };
    }

    /**
     * The direct-return DML mutation: {@code @mutation(typeName:)} on a field returning the
     * {@code @table} type or an encoded ID. The write payload ({@link #write()}, the
     * {@link DmlWriteField} capability) carries the verb identity and the per-verb input
     * surface in one sealed home: the Insert / Upsert arms the {@code @table}
     * {@link ArgumentRef.InputTypeArg.TableInputArg} that drives the statement directly, the
     * Update / Delete arms the slim {@link InputArgRef} arg surface plus their walker-produced
     * carrier ({@link UpdateRows} / {@link DeleteRows}), because input fields have no
     * semantics independent of the consuming field, so the SET/WHERE partition lives on the
     * carrier. The pre-resolved {@link DmlReturnExpression} arm captures the entire
     * return-shape dispatch (encoded ID, projected {@code @table}, or discriminated
     * interface); the classifier picks it once and emitters pattern-match on
     * {@link #returnExpression()}.
     *
     * <p>Construction invariant: a Delete write arm carries only {@code Encoded*} return
     * arms. The row is gone after the statement and RETURNING carries only the primary key,
     * so no follow-up projection exists to feed a table-bound return; the classifier rejects
     * DELETE with any table-bound return at authoring time, and the constructor makes the
     * pairing structural, which keeps the validator's reentry key-arity check provably
     * vacuous for Delete.
     */
    record DmlTableField(
        String parentTypeName,
        String name,
        SourceLocation location,
        DmlReturnExpression returnExpression,
        OperationMember.Write.Dml write,
        Optional<ErrorChannel.RouterDispatched> errorChannel
    ) implements MutationField, DmlWriteField {

        public DmlTableField {
            Objects.requireNonNull(returnExpression, "returnExpression");
            Objects.requireNonNull(write, "write");
            if (write instanceof OperationMember.Write.Delete
                    && !(returnExpression instanceof DmlReturnExpression.EncodedSingle
                        || returnExpression instanceof DmlReturnExpression.EncodedList)) {
                throw new IllegalArgumentException(
                    "a DELETE mutation cannot carry a table-bound return ("
                    + returnExpression.getClass().getSimpleName() + "): DELETE removes the row, "
                    + "and RETURNING carries only the primary key, so a projection of the "
                    + "written row is impossible. The @mutation classifier rejects "
                    + "DELETE with a @table return at authoring time; this carrier only ever "
                    + "holds an encoded-ID return beside a Delete arm.");
            }
        }

        /**
         * The verb's typed dialect constraint, derived from the write arm and the input
         * cardinality it carries, so the pairing has one home and cannot drift: UPSERT
         * rejects Oracle (jOOQ silently mistranslates {@code ON CONFLICT} to
         * {@code MERGE INTO} there), a bulk (list-input) UPDATE requires PostgreSQL (the
         * {@code UPDATE ... FROM (VALUES ...)} form is a Postgres extension), and every other
         * combination is unconstrained. The emitter renders the request-time guard from this
         * arm.
         */
        public DialectRequirement dialectRequirement() {
            return switch (write) {
                case OperationMember.Write.Update u when u.listInput() ->
                    new DialectRequirement.RequiresFamily(SqlDialectFamily.POSTGRES,
                        "@mutation(typeName: UPDATE) with a listed @table input requires PostgreSQL; "
                            + "the UPDATE ... FROM (VALUES ...) form is a Postgres extension. "
                            + "Use a single-row input for portability.");
                case OperationMember.Write.Upsert _ ->
                    new DialectRequirement.RejectsFamily(SqlDialectFamily.ORACLE,
                        "@mutation(typeName: UPSERT) is not supported on Oracle; jOOQ silently "
                            + "mistranslates the ON CONFLICT upsert to MERGE INTO there. Use a "
                            + "different dialect, or split the upsert into INSERT and UPDATE.");
                default -> DialectRequirement.None.INSTANCE;
            };
        }

        @Override public DomainReturnType domainReturnType() {
            return dmlDomainReturnType(returnExpression, write.table());
        }
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
     * <p>The chain shape is the shared {@link RoutineChain} (the same carrier the query root's
     * {@link RoutineResolution.Chain} source arm holds); this leaf adds two pins of its own:
     * {@code hops} non-empty (with no hop there is no post-commit table to re-read from; the
     * single-node Mutation {@code @routine} classifies as a typed {@code Deferred}), and hop 0
     * joining by {@link On.ColumnPairs} (the classifier's re-read-anchor verdict routes every
     * other shape to a typed {@code Deferred}, and the emitter's key capture reads the pairs).
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
    ) implements MutationField {

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
            // Hop 0 joins by column pairs, so the post-commit re-read has a key to capture. The
            // classifier's re-read-anchor verdict routes every other shape to a typed Deferred;
            // the emitter narrows to On.ColumnPairs on this pin's authority.
            if (!(((JoinStep.Hop) chain.hops().get(0)).on() instanceof On.ColumnPairs)) {
                throw new IllegalArgumentException(
                    "MutationRoutineWriteField hop 0 must join by column pairs (On.ColumnPairs); "
                    + "the classifier's re-read-anchor verdict admits no other shape");
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
     * <p>The write payload ({@link #write()}, the {@link DmlWriteField} capability) carries the
     * verb identity and the per-verb input surface in one sealed home; the emitter forks on
     * the arm. The Insert and Upsert arms drive the statement off the {@code @table}
     * {@link ArgumentRef.InputTypeArg.TableInputArg}; the Update and Delete arms source their
     * SET/WHERE partition from the walker carrier ({@link UpdateRows} / {@link DeleteRows})
     * riding the arm, never from a {@code TableInputArg}. Every arm reads
     * {@code write.table().primaryKeyColumns()} for the PK-only {@code RETURNING} clause of
     * the two-step DML (DELETE's PK echo is the whole post-image; the others follow up with
     * the data field's response SELECT).
     *
     * <p>{@link #returnType()} is the carrier's {@link ReturnTypeRef.ResultReturnType} with no
     * unwrap: the SDL's structural truth.
     */
    record MutationDmlRecordField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef.ResultReturnType returnType,
        OperationMember.Write.Dml write,
        Optional<ErrorChannel.RouterDispatched> errorChannel
    ) implements MutationField, DmlWriteField {

        public MutationDmlRecordField {
            Objects.requireNonNull(write, "write");
        }
        @Override public DomainReturnType domainReturnType() {
            return new DomainReturnType.Record(write.table());
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
     * <p>The classifier admits
     * {@code (write.listInput() == true, dataField.wrapper().isList() == true)} and pairs the
     * input cardinality to the data field's element type; the Insert, Update and Delete write
     * arms are live, while UPSERT is structurally compatible with this leaf but refused at
     * the classifier's verb dispatch under the cardinality-safety regime (the compact
     * constructor backstops that refusal). The data table / input table agreement is structurally pinned by
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
     * <p>The write payload ({@link #write()}, the {@link DmlWriteField} capability) encodes
     * the per-emit-shape dispatch: the Insert arm drives per-row statements off the
     * {@code @table} input, the Update and Delete arms off their walker carriers.
     *
     * @see no.sikt.graphitron.rewrite.generators.TypeFetcherGenerator
     */
    record MutationBulkDmlRecordField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef.ResultReturnType returnType,
        OperationMember.Write.Dml write,
        Optional<ErrorChannel.RouterDispatched> errorChannel
    ) implements MutationField, DmlWriteField {

        public MutationBulkDmlRecordField {
            Objects.requireNonNull(write, "write");
            if (write instanceof OperationMember.Write.Upsert) {
                throw new IllegalArgumentException(
                    "MutationBulkDmlRecordField cannot carry an Upsert write arm under the "
                    + "cardinality-safety regime; UPSERT is refused at the classifier pending "
                    + "a designed cardinality story.");
            }
            if (!write.listInput()) {
                throw new IllegalArgumentException(
                    "MutationBulkDmlRecordField requires a bulk (list) input surface "
                    + "(write.listInput() == true); single-input belongs on "
                    + "MutationDmlRecordField.");
            }
        }
        @Override public DomainReturnType domainReturnType() {
            return new DomainReturnType.Record(write.table());
        }
    }

}
