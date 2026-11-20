package no.sikt.graphitron.rewrite.model;

import graphql.language.SourceLocation;
import no.sikt.graphitron.rewrite.ArgumentRef;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import no.sikt.graphitron.render.CatalogRefs;
import no.sikt.graphitron.model.diagnostics.Arity;
import no.sikt.graphitron.model.jooq.ColumnRef;
import no.sikt.graphitron.model.diagnostics.Rejection;
import no.sikt.graphitron.model.jooq.TableRef;

/**
 * A field on the {@code Mutation} type. The only fields permitted to write to the database.
 *
 * <p>Every variant is fetcher-emitting and implements {@link WithErrorChannel}: a populated
 * channel makes the emitter wrap the fetcher body in a try/catch routing thrown exceptions
 * into the typed {@code errors} field. An {@link Optional#empty()} channel means no
 * {@code errors} field on the payload, not an unclassified one.
 */
public sealed interface MutationField extends RootField, WithErrorChannel
    permits MutationField.DmlTableField, MutationField.MutationRoutineWriteField,
            MutationField.MutationRoutineWriteRecordField,
            MutationField.MutationDmlRecordField,
            MutationField.MutationBulkDmlRecordField {

    /**
     * The DELETE leaves' shared invariant: an idempotent DELETE's echo supplies exactly the columns
     * the {@code RETURNING} captures beyond the primary key, in the same order, so an echoed row and
     * a deleted row are records of one shape.
     */
    private static void requireEchoMatchesReturning(OperationMember.Write.Dml write, Returning returning) {
        Objects.requireNonNull(returning, "returning");
        if (write instanceof OperationMember.Write.Delete d
                && d.deleteRows() instanceof DeleteRows.IdempotentByPrimaryKey idem) {
            var echoed = idem.echoBinding().stream().map(kc -> kc.targetColumn().sqlName()).toList();
            var tail = returning.nodeKeyTail().stream().map(ColumnRef::sqlName).toList();
            if (!echoed.equals(tail)) {
                throw new IllegalArgumentException(
                    "the idempotent DELETE's echo binds " + echoed + " but RETURNING captures " + tail
                    + " beyond the primary key; an echoed row must have the deleted row's shape");
            }
        }
    }

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
            // Routine carrier: the fetcher's value is the captured key record; step 2 belongs
            // to the payload's data field, exactly as on the DML record carriers below.
            case MutationRoutineWriteRecordField f -> OutputField.listOrSingle(f.returnType().wrapper(), new TargetShape.Record());
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
     * vacuous for Delete. A Delete arm's echo of a key that matched no row is held to what
     * {@link #deleteReturning()} captures, as on the record carriers.
     */
    record DmlTableField(
        String parentTypeName,
        String name,
        SourceLocation location,
        DmlReturnExpression returnExpression,
        OperationMember.Write.Dml write
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
            if (write instanceof OperationMember.Write.Delete) {
                requireEchoMatchesReturning(write, deleteReturningOf(returnExpression, write).orElseThrow());
            }
        }

        /**
         * What a Delete arm's {@code RETURNING} captures: the primary key followed by the encoded
         * node key's columns outside it, the value the record carriers hold as their
         * {@code returning} component. Both the echo check and the emitted {@code RETURNING} read
         * it, so they cannot disagree. Empty off the Delete arm.
         */
        public Optional<Returning> deleteReturning() {
            return deleteReturningOf(returnExpression, write);
        }

        // Static so the compact constructor can read it before the components are assigned.
        private static Optional<Returning> deleteReturningOf(DmlReturnExpression returnExpression,
                                                             OperationMember.Write.Dml write) {
            if (!(write instanceof OperationMember.Write.Delete)) {
                return Optional.empty();
            }
            var encode = switch (returnExpression) {
                case DmlReturnExpression.EncodedSingle e -> e.encode();
                case DmlReturnExpression.EncodedList e -> e.encode();
                default -> throw new IllegalStateException(
                    "a Delete arm carries only an encoded return; the compact constructor holds this");
            };
            return Optional.of(Returning.widenedToNodeKey(write.table(), encode.paramSignature()));
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

        /**
         * Pinned empty: the field returns its {@code @table} type or an encoded ID directly, so
         * there is no payload to put an errors field on.
         */
        @Override public Optional<ErrorChannel.Mapped> errorChannel() {
            return Optional.empty();
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
     * <p>{@code errorChannel()} is pinned empty: this leaf is the direct-terminus shape, whose
     * return is the terminus {@code @table} type itself, so there is no payload field to put a
     * typed {@code errors} list in; the fetcher wraps in the no-channel redacting catch arm. The
     * carrier shape, whose payload return does carry a channel, is the sibling leaf
     * {@link MutationRoutineWriteRecordField}. An SQL error from the routine rolls
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

        @Override public Optional<ErrorChannel.Mapped> errorChannel() {
            return Optional.empty();
        }

        @Override public DomainReturnType domainReturnType() {
            return new DomainReturnType.Record(returnType.table());
        }
    }

    /**
     * A mutation field whose write is a hop-less {@code @routine} call and whose return is a
     * payload carrier (an SDL Object admitted by
     * {@code BuildContext.scanStructuralRoutineCarrierPayload} as a single {@code @table}-element
     * data field plus an optional errors-shaped field). Sibling to
     * {@link MutationRoutineWriteField} exactly as {@link MutationDmlRecordField} sits beside
     * {@link DmlTableField}: the direct leaf projects the terminus itself, this leaf's fetcher
     * owns step 1 alone and step 2 belongs to the payload's data field (a record-sourced
     * {@link ChildField.BatchedTableField}).
     *
     * <p><b>The two-statements rule.</b> The write transaction contains the routine call and a
     * projection of the routine's own result columns, nothing else, ever: step 1 executes the
     * routine inside {@code dsl.transactionResult(...)} and captures the {@link #capturedPairs()}
     * source columns off the routine's result rows, projected under the target table's key
     * fields; anything beyond that capture is a post-commit follow-up query owned by a payload
     * field, running at read time under the caller's identity.
     *
     * <p>The compact constructor pins that rule into the type: the pairs are non-empty, their
     * keying is name-matched (a name-matched hop 0 is precisely the case where step 1 needs no
     * join), and their target side equals the target table's primary-key columns (the value the
     * data field's hop-less {@code OnLiftedSlots} correlation reads back). No chain, no hops,
     * no hop-count component; the data field's path is the implicit single name-matched hop,
     * derived once at grounding ({@link ProducerBinding.RoutineEmitted}).
     *
     * <p>{@code dataFieldArrival} is the payload data field's SDL wrapper cardinality, the only
     * cardinality claim in the system for this shape (jOOQ types every table-valued function as
     * a {@code Table<R>}, so the catalog carries no per-call cardinality fact). It drives step
     * 1's {@code fetch()} vs {@code fetchOne()}.
     *
     * <p>The channel slot is {@link ErrorChannel.Mapped}: the fetcher returns the typed
     * {@code Outcome}, so the errors travel on the payload object and graphql-java's
     * {@code localContext} stays free for the tenant hand-down. The two null-data outcomes are
     * distinct and only one goes through the channel: (a) the routine raised, the catch arm
     * returns {@code Outcome.ErrorList} and the data field renders null; (b) the routine
     * succeeded and the post-commit re-read cannot see the row (row-level security), data field
     * null, {@code errors} null.
     */
    record MutationRoutineWriteRecordField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef.ResultReturnType returnType,
        RoutineRef routine,
        TableRef routineResultTable,
        List<JoinSlot.FkSlot> capturedPairs,
        TableRef targetTable,
        Arity dataFieldArrival,
        Optional<ErrorChannel.Mapped> errorChannel
    ) implements MutationField {

        public MutationRoutineWriteRecordField {
            Objects.requireNonNull(returnType, "returnType");
            Objects.requireNonNull(routine, "routine");
            Objects.requireNonNull(routineResultTable, "routineResultTable");
            Objects.requireNonNull(targetTable, "targetTable");
            Objects.requireNonNull(dataFieldArrival, "dataFieldArrival");
            Objects.requireNonNull(errorChannel, "errorChannel");
            capturedPairs = List.copyOf(capturedPairs);
            if (capturedPairs.isEmpty()) {
                throw new IllegalArgumentException(
                    "MutationRoutineWriteRecordField requires non-empty capturedPairs: with no "
                    + "captured key there is nothing for the payload data field's re-read to "
                    + "correlate on");
            }
            // The two-statements rule as a type invariant: name-matched keying (step 1 needs no
            // join) and the pairs' target side is exactly the target table's primary key (the
            // value the data field's OnLiftedSlots correlation reads back).
            for (var pair : capturedPairs) {
                if (!pair.sourceSide().sqlName().equalsIgnoreCase(pair.targetSide().sqlName())) {
                    throw new IllegalArgumentException(
                        "MutationRoutineWriteRecordField capturedPairs must be name-matched; "
                        + "pair (" + pair.sourceSide().sqlName() + " -> "
                        + pair.targetSide().sqlName() + ") is not");
                }
            }
            var targetSides = capturedPairs.stream().map(JoinSlot.FkSlot::targetSide).toList();
            if (!targetSides.equals(targetTable.primaryKeyColumns())) {
                throw new IllegalArgumentException(
                    "MutationRoutineWriteRecordField capturedPairs' target side must equal the "
                    + "target table's primary-key columns (" + targetTable.tableName() + "); the "
                    + "data field's correlation reads the captured record by those columns");
            }
        }

        @Override public DomainReturnType domainReturnType() {
            return new DomainReturnType.Record(targetTable);
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
     * riding the arm, never from a {@code TableInputArg}. Every arm reads {@link #returning()} for
     * the {@code RETURNING} clause of the two-step DML: the primary key, widened to the node key on
     * a DELETE whose payload ids need it (DELETE's echo is the whole post-image; the others follow
     * up with the data field's response SELECT).
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
        Returning returning,
        Optional<ErrorChannel.Mapped> errorChannel
    ) implements MutationField, DmlWriteField {

        public MutationDmlRecordField {
            Objects.requireNonNull(write, "write");
            requireEchoMatchesReturning(write, returning);
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
     * {@code reflectedClass.getName().equals(CatalogRefs.recordClass(tableRef).reflectionName())}
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
        Returning returning,
        Optional<ErrorChannel.Mapped> errorChannel
    ) implements MutationField, DmlWriteField {

        public MutationBulkDmlRecordField {
            Objects.requireNonNull(write, "write");
            requireEchoMatchesReturning(write, returning);
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
