package no.sikt.graphitron.rewrite.model;

import graphql.language.SourceLocation;

import java.util.List;
import java.util.Optional;

/**
 * A field on a non-root output type. Source context (table-mapped or result-mapped) is
 * determined by the parent {@link no.sikt.graphitron.rewrite.model.GraphitronType} at generation time.
 */
public sealed interface ChildField extends GraphitronField
    permits ChildField.ColumnField, ChildField.ColumnReferenceField,
            ChildField.ParticipantColumnReferenceField,
            ChildField.CompositeColumnField, ChildField.CompositeColumnReferenceField,
            ChildField.TableTargetField,
            ChildField.TableMethodField,
            ChildField.RecordTableMethodField,
            ChildField.InterfaceField, ChildField.UnionField,
            ChildField.ConstructorField, ChildField.NestingField,
            ChildField.ServiceRecordField,
            ChildField.RecordField,
            ChildField.ComputedField, ChildField.PropertyField,
            ChildField.SingleRecordIdentityField,
            ChildField.SingleRecordIdFieldFromReturning,
            ChildField.SingleRecordTableFieldFromReturning,
            ChildField.ErrorsField {

    /**
     * R75 / Phase 1 — the single data field on a single-record DML carrier (an SDL Object passing
     * {@link no.sikt.graphitron.rewrite.BuildContext#tryResolveSingleRecordCarrier})
     * for a record-returning DML mutation. The parent mutation classifies as
     * {@code MutationField.MutationDmlRecordField}; its fetcher returns
     * {@code Result<RecordN<...>>} carrying the upstream DML's PK-only RETURNING rows,
     * and this data field's fetcher reads {@code env.getSource()} typed by
     * {@code SourceKey.wrap × columns}, then runs the response SELECT outside the DML
     * transaction.
     *
     * <p>Implements {@link TableTargetField} for structural uniformity with the existing
     * {@code RecordTableField} family; does not implement {@link BatchKeyField} because there
     * is no DataLoader setup — the single source row is in hand at fetch time, not batched
     * across multiple parent rows. See {@code synthesize-payload-carrier.md}, "New sibling
     * permit: ChildField.SingleRecordTableField", for the design rationale.
     *
     * <p>The {@link #sourceKey()} component is pinned by the {@code SourceKey} compact-constructor
     * invariant {@code source-key.result-row-walk-wrap-record-empty-path} to use
     * {@link SourceKey.Reader.ResultRowWalk} with {@link SourceKey.Wrap.Record}. {@link #joinPath()},
     * {@link #filters()}, {@link #orderBy()}, and {@link #pagination()} are structurally empty/None
     * for this permit (no navigation, no WHERE, no ordering, no pagination); accessor methods return
     * empty/{@code None}/{@code null} rather than storing the values, encoding the invariant in the
     * type system per the "narrow component types" principle.
     */
    record SingleRecordTableField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef.TableBoundReturnType returnType,
        SourceKey sourceKey
    ) implements TableTargetField {

        public SingleRecordTableField {
            if (!(sourceKey.reader() instanceof SourceKey.Reader.ResultRowWalk)) {
                throw new IllegalArgumentException(
                    "SingleRecordTableField requires SourceKey with Reader.ResultRowWalk; got "
                    + sourceKey.reader().getClass().getSimpleName());
            }
        }

        @Override public List<JoinStep> joinPath() { return List.of(); }
        @Override public List<WhereFilter> filters() { return List.of(); }
        @Override public OrderBySpec orderBy() { return new OrderBySpec.None(); }
        @Override public PaginationSpec pagination() { return null; }
    }

    /**
     * R75 / Phase 2 — the single data field on a single-record carrier whose element type is
     * record-backed ({@link GraphitronType.PojoResultType.Backed}, {@link GraphitronType.JavaRecordType},
     * or a jOOQ {@code Record}-backed {@link GraphitronType.ResultType}). The parent classifies as
     * {@link MutationField.MutationServiceRecordField} returning the domain record directly
     * (after the existing {@code @service} wrapper unwrap); this data field's value IS the source
     * value verbatim. Fetcher emit is identity passthrough — {@code env -> env.getSource()} —
     * with no {@link SourceKey} synthesis (no source rows to extract; the parent's value is the
     * data field's value). Declines {@link TableTargetField} (no table to target) and
     * {@link BatchKeyField} (no DataLoader); the read-mechanism axis is explicit at the type-system
     * level rather than overloading {@code ConstructorField} per the spec's "deliberate spec-time
     * fork" reasoning.
     */
    record SingleRecordIdentityField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef.ResultReturnType returnType
    ) implements ChildField {}

    /**
     * R156 — the single data field on a payload-returning DELETE carrier whose
     * {@link DataElement.Id} arm encodes the deleted row's primary key as an {@code ID} scalar.
     * The parent classifies as {@code MutationField.MutationDmlRecordField} (single DELETE) or
     * {@code MutationField.MutationBulkDmlRecordField} (bulk DELETE) returning the PK-only
     * RETURNING rows; this data field's fetcher reads PK column(s) off the source {@code Record}
     * and runs them through {@link #encode} to produce the encoded NodeId.
     *
     * <p>Sibling of {@link SingleRecordTableField} ("follow-up SELECT outside the tx,"
     * load-bearing for INSERT / UPDATE / UPSERT carriers) and
     * {@link SingleRecordTableFieldFromReturning} ("no follow-up read; source IS the RETURNING
     * record; per-field projection via {@link PkResolution}," load-bearing for DELETE +
     * {@code DataElement.Table}). The three encode genuinely different invariants — follow-up
     * SELECT vs no-follow-up vs no-follow-up-encoded-PK — not different values of one knob; the
     * sealed split pushes the per-carrier emission story into the type system. The existing
     * {@link SingleRecordTableField} is unchanged.
     *
     * <p>Declines {@link TableTargetField} (element is the {@code ID} scalar, not a table-bound
     * type) and {@link BatchKeyField} (no DataLoader). The {@link #encode} compaction carries
     * the resolved per-Node encoder and the column shape, mirroring the
     * {@link CallSiteCompaction.NodeIdEncodeKeys} slot every other NodeId-encoded projection
     * uses; mixing the encoder into {@link DataElement.Id} would conflate the "what's the
     * element shape" and "how to project it" axes the rewrite already splits.
     */
    record SingleRecordIdFieldFromReturning(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef.ScalarReturnType returnType,
        CallSiteCompaction.NodeIdEncodeKeys encode
    ) implements ChildField {}

    /**
     * R156 — the single data field on a payload-returning DELETE carrier whose
     * {@link DataElement.Table} element type projects the PK-only RETURNING record onto a
     * {@code @table}-backed SDL type. The parent classifies as
     * {@code MutationField.MutationDmlRecordField} (single DELETE) or
     * {@code MutationField.MutationBulkDmlRecordField} (bulk DELETE); the fetcher reads each
     * per-field arm of {@link #projection} off the source {@code Record} (no follow-up SELECT —
     * the row is gone).
     *
     * <p>{@link #projection} is the narrow two-arm {@link PkResolution} list, the projected
     * result of the carrier walk's per-field {@code PerFieldOutcome} classification. The only
     * producer is {@code BuildContext.classifyDeleteTableProjection}, which rejects the carrier
     * before constructing any element if the classification surfaces a {@code NonPkNonNullable},
     * {@code ServiceField}, or {@code UnsupportedField} arm. The emitter's sealed switch on
     * {@link PkResolution} is therefore exhaustive over its two arms with no defensive default
     * — the rejection arms cannot reach this carrier by type.
     *
     * <p>Sibling of {@link SingleRecordTableField} (which carries the "follow-up SELECT outside
     * the tx" invariant for INSERT / UPDATE / UPSERT) and {@link SingleRecordIdFieldFromReturning}
     * (which carries the "encoded PK scalar" invariant for DELETE + {@link DataElement.Id}). The
     * three are genuinely different invariants, not discriminator variants of one knob; the
     * sealed split pushes the per-carrier emission story into the type system. The existing
     * {@link SingleRecordTableField} is unchanged.
     *
     * <p>Declines {@link TableTargetField} (no SQL is generated for this carrier — the fetcher
     * reads off the RETURNING record) and {@link BatchKeyField} (no DataLoader).
     */
    record SingleRecordTableFieldFromReturning(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef.TableBoundReturnType returnType,
        List<PkResolution> projection
    ) implements ChildField {
        public SingleRecordTableFieldFromReturning {
            projection = List.copyOf(projection);
        }
    }

    /**
     * A single-column output carrier on a table-backed parent. The column's value reaches the
     * field's value through {@link #compaction()}: {@link CallSiteCompaction.Direct} for plain
     * SELECT-term projection, {@link CallSiteCompaction.NodeIdEncodeKeys} for arity-1
     * {@code @nodeId} projections that wrap the column in the per-Node
     * {@code encode<TypeName>} helper.
     */
    record ColumnField(
        String parentTypeName,
        String name,
        SourceLocation location,
        String columnName,
        ColumnRef column,
        CallSiteCompaction compaction
    ) implements ChildField {}

    /**
     * A single-column output carrier on a table-backed parent reached through a {@code @reference}
     * path. The terminal column lives on the joined target table; {@link #compaction()} controls
     * how the value reaches the field — {@link CallSiteCompaction.Direct} for plain projection,
     * {@link CallSiteCompaction.NodeIdEncodeKeys} for arity-1 {@code @nodeId} references where
     * the parent table's keyColumns sit on the joined parent (rooted-at-parent single-hop) or
     * the FK source columns on the child positionally mirror the keyColumns (rooted-at-child).
     */
    record ColumnReferenceField(
        String parentTypeName,
        String name,
        SourceLocation location,
        String columnName,
        ColumnRef column,
        List<JoinStep> joinPath,
        CallSiteCompaction compaction
    ) implements ChildField {}

    /**
     * A scalar field on a {@link GraphitronType.TableInterfaceType} participant that
     * resolves to a column on a different table than the participant's own (i.e. via a
     * single-hop {@code @reference}). The interface fetcher emits a conditional LEFT JOIN
     * gated by the participant's discriminator value and projects the column aliased as
     * {@link #aliasName}; the per-field DataFetcher reads it back from the result
     * {@code Record} by that alias.
     *
     * <p>Distinct from {@link ColumnReferenceField} (the broader, still-stubbed
     * scalar-{@code @reference} story): this variant exists specifically
     * for the {@code TableInterfaceType} cross-table participant-field case where the
     * interface fetcher (not a per-field method) materialises the value.
     */
    record ParticipantColumnReferenceField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ColumnRef column,
        JoinStep.FkJoin fkJoin,
        String aliasName
    ) implements ChildField {
        /** The cross table joined to project this field — equivalent to {@code fkJoin().targetTable()}. */
        public TableRef targetTable() { return fkJoin.targetTable(); }
    }

    /**
     * Composite-key output carrier on a table-backed parent reached through a {@code @reference}
     * path. Carries {@code columns} of arity &ge; 2 plus a single-hop or correlated-subquery
     * {@code joinPath}. {@code compaction} is narrowed to {@link CallSiteCompaction.NodeIdEncodeKeys}
     * at the type level: a composite-column reference projection is always a NodeId encode call;
     * no plain composite-column reference projection exists.
     */
    record CompositeColumnReferenceField(
        String parentTypeName,
        String name,
        SourceLocation location,
        List<ColumnRef> columns,
        List<JoinStep> joinPath,
        CallSiteCompaction.NodeIdEncodeKeys compaction
    ) implements ChildField {

        public CompositeColumnReferenceField {
            columns = List.copyOf(columns);
            if (columns.size() < 2) {
                throw new IllegalArgumentException(
                    "CompositeColumnReferenceField requires arity >= 2 (got " + columns.size() + "); arity-1 routes to ColumnReferenceField");
            }
        }
    }

    /**
     * Composite-key output carrier on a table-backed parent. Carries {@code columns} of arity
     * &ge; 2 (arity-1 routes to the single-column {@link ColumnField} sibling) and a
     * {@link CallSiteCompaction.NodeIdEncodeKeys} compaction whose
     * {@link HelperRef.Encode#paramSignature() encodeMethod.paramSignature} is positionally
     * equal to the NodeType's {@code keyColumns}. The slot is narrowed to
     * {@code NodeIdEncodeKeys} at the type level: no plain composite-column projection exists.
     */
    record CompositeColumnField(
        String parentTypeName,
        String name,
        SourceLocation location,
        List<ColumnRef> columns,
        CallSiteCompaction.NodeIdEncodeKeys compaction
    ) implements ChildField {

        public CompositeColumnField {
            columns = List.copyOf(columns);
            if (columns.size() < 2) {
                throw new IllegalArgumentException(
                    "CompositeColumnField requires arity >= 2 (got " + columns.size() + "); arity-1 routes to ColumnField");
            }
        }
    }

    /**
     * A child field that navigates to (or stays at) a table scope and generates SQL.
     *
     * <p>All eight variants carry the same three SQL-generation components in addition to the
     * core {@link ReturnTypeRef.TableBoundReturnType returnType} and join path:
     * <ul>
     *   <li>{@link #filters()} — ordered list of WHERE-clause contributions ({@link WhereFilter});
     *       may be empty. {@link ConditionFilter} entries represent field-level {@code @condition}
     *       methods. {@link GeneratedConditionFilter} entries represent Graphitron-generated
     *       argument-driven predicates.</li>
     *   <li>{@link #orderBy()} — authoritative ordering ({@link OrderBySpec}); always non-null.
     *       {@link OrderBySpec.None} when ordering is not applicable or not resolvable.</li>
     *   <li>{@link #pagination()} — Relay pagination arguments ({@link PaginationSpec});
     *       {@code null} when the field has no pagination arguments.</li>
     * </ul>
     *
     * <p>{@link NestingField} is intentionally excluded: it carries a
     * {@link ReturnTypeRef.TableBoundReturnType} but does not navigate — it inherits the parent's
     * table context unchanged.
     */
    sealed interface TableTargetField extends ChildField, SqlGeneratingField
        permits ChildField.TableField, ChildField.SplitTableField,
                ChildField.LookupTableField, ChildField.SplitLookupTableField,
                ChildField.TableInterfaceField,
                ChildField.ServiceTableField,
                ChildField.RecordTableField, ChildField.RecordLookupTableField,
                ChildField.SingleRecordTableField {

        ReturnTypeRef.TableBoundReturnType returnType();
        List<JoinStep> joinPath();
        List<WhereFilter> filters();
        OrderBySpec orderBy();
        PaginationSpec pagination();
    }

    record TableField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef.TableBoundReturnType returnType,
        List<JoinStep> joinPath,
        List<WhereFilter> filters,
        OrderBySpec orderBy,
        PaginationSpec pagination
    ) implements TableTargetField {}

    record SplitTableField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef.TableBoundReturnType returnType,
        List<JoinStep> joinPath,
        List<WhereFilter> filters,
        OrderBySpec orderBy,
        PaginationSpec pagination,
        SourceKey sourceKey,
        LoaderRegistration loaderRegistration
    ) implements TableTargetField, BatchKeyField, ConditionJoinReportable {
        @Override
        public boolean emitsSingleRecordPerKey() {
            return !returnType().wrapper().isList();
        }
        @Override public Rejection.EmitBlockReason emitBlockReason() {
            return Rejection.EmitBlockReason.SPLIT_TABLE_FIELD_CONDITION_JOIN_STEP;
        }
        @Override public String displayLabel() { return "@splitQuery"; }
    }

    record LookupTableField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef.TableBoundReturnType returnType,
        List<JoinStep> joinPath,
        List<WhereFilter> filters,
        OrderBySpec orderBy,
        PaginationSpec pagination,
        LookupMapping lookupMapping
    ) implements TableTargetField, LookupField {}

    record SplitLookupTableField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef.TableBoundReturnType returnType,
        List<JoinStep> joinPath,
        List<WhereFilter> filters,
        OrderBySpec orderBy,
        PaginationSpec pagination,
        SourceKey sourceKey,
        LoaderRegistration loaderRegistration,
        LookupMapping lookupMapping
    ) implements TableTargetField, BatchKeyField, LookupField, ConditionJoinReportable {
        @Override public Rejection.EmitBlockReason emitBlockReason() {
            return Rejection.EmitBlockReason.SPLIT_LOOKUP_TABLE_FIELD_CONDITION_JOIN_STEP;
        }
        @Override public String displayLabel() { return "@splitQuery @lookupKey"; }
    }

    /**
     * A child field using {@code @tableMethod} — the developer provides a pre-filtered
     * {@code Table<?>}. The method handles all SQL generation.
     *
     * <p>The method signature is:
     * <pre>
     *     Table&lt;?&gt; method(Table&lt;?&gt; targetTable, arg1, arg2, ...)
     * </pre>
     * where the table parameter has {@link ParamSource.Table} as its source, and subsequent
     * parameters have {@link ParamSource.Arg} or {@link ParamSource.Context}.
     *
     * <p>The return type is always a {@link ReturnTypeRef.TableBoundReturnType}: the directive
     * exists to bind a developer-authored jOOQ table method, which by construction returns a
     * generated jOOQ table class. {@link TableMethodDirectiveResolver} rejects any other return
     * shape as a schema error (R43).
     */
    @DependsOnClassifierCheck(
        key = "tablemethod-resolver-return-is-table-bound",
        reliesOn = "Declares returnType with the narrow ReturnTypeRef.TableBoundReturnType "
            + "component type. Downstream consumers reach .table() / .table().tableClass() "
            + "without a sealed-switch or instanceof guard — the type signature is the "
            + "load-bearing consumer of the resolver's structural rejection.")
    record TableMethodField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef.TableBoundReturnType returnType,
        List<JoinStep> joinPath,
        MethodRef method,
        Optional<ErrorChannel> errorChannel
    ) implements ChildField, MethodBackedField, WithErrorChannel {}

    /**
     * DTO-parent sibling of {@link TableMethodField}: a child field on a {@code @record} (non-table)
     * parent that uses {@code @tableMethod} to bind a developer-authored static jOOQ table method.
     * The parent has no parent-table alias to join from, so the developer's table is joined against
     * a DataLoader-keyed batch lifted out of the parent DTO via {@link #sourceKey}, mirroring the
     * existing {@link RecordTableField} / {@link RecordLookupTableField} pattern. {@link #joinPath}
     * is the resolved parent-to-target chain: {@code [fkJoin]} for the FK-auto-derive arm on a
     * jOOQ-table-record-backed parent, the lifter's resolved chain on the {@code @sourceRow} arm.
     *
     * <p>The {@code @LoadBearingClassifierCheck} key {@code tablemethod-resolver-return-is-table-bound}
     * is shared with {@link TableMethodField}: the directive resolver narrows the returnType to
     * {@link ReturnTypeRef.TableBoundReturnType} for both producers.
     */
    @DependsOnClassifierCheck(
        key = "tablemethod-resolver-return-is-table-bound",
        reliesOn = "Declares returnType with the narrow ReturnTypeRef.TableBoundReturnType "
            + "component type. Downstream consumers reach .table() / .table().tableClass() "
            + "without a sealed-switch or instanceof guard.")
    record RecordTableMethodField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef.TableBoundReturnType returnType,
        List<JoinStep> joinPath,
        MethodRef method,
        SourceKey sourceKey,
        LoaderRegistration loaderRegistration,
        Optional<ErrorChannel> errorChannel
    ) implements ChildField, MethodBackedField, BatchKeyField, WithErrorChannel {
        @Override
        public boolean emitsSingleRecordPerKey() {
            return !returnType().wrapper().isList()
                || loaderRegistration().dispatch() == LoaderRegistration.Dispatch.LOAD_MANY;
        }
    }

    record TableInterfaceField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef.TableBoundReturnType returnType,
        String discriminatorColumn,
        List<String> knownDiscriminatorValues,
        List<ParticipantRef> participants,
        List<JoinStep> joinPath,
        List<WhereFilter> filters,
        OrderBySpec orderBy,
        PaginationSpec pagination
    ) implements TableTargetField {}

    /**
     * A child field on a {@link GraphitronType.TableBackedType} parent returning a multi-table
     * {@link GraphitronType.InterfaceType}. Carries the resolved participants list plus the
     * per-participant {@code joinPath} (one auto-discovered FK chain from the parent table to
     * each participant's table) so the multi-table polymorphic emitter can emit a per-branch
     * WHERE in the stage-1 narrow UNION ALL.
     *
     * <p>{@code participantJoinPaths} is keyed by participant typename — exactly one entry per
     * {@link ParticipantRef.TableBound} participant. {@link ParticipantRef.Unbound} participants
     * are absent from the map; they contribute no SQL branch.
     *
     * <p>{@code parentSourceKey} and {@code parentResultType} are the parent-object key-extraction
     * strategy and shape, threaded into {@code GeneratorUtils.buildRecordParentKeyExtraction}.
     * Through R102 the classifier produces only catalog-FK / {@code ColumnRead}-reader parent
     * source-keys (table-backed parents); R105 wires the {@code @record}-parent classifier arm
     * to reach the lifter and accessor reader permits.
     */
    record InterfaceField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef.PolymorphicReturnType returnType,
        List<ParticipantRef> participants,
        java.util.Map<String, List<JoinStep>> participantJoinPaths,
        SourceKey parentSourceKey,
        GraphitronType.ResultType parentResultType
    ) implements ChildField {
        public InterfaceField {
            participants = List.copyOf(participants);
            participantJoinPaths = java.util.Map.copyOf(participantJoinPaths);
            // R105 follow-up: validator and emitter both read parentSourceKey / parentResultType
            // unconditionally; carry the non-null contract in the type system rather than
            // by reviewer-tracked correspondence.
            java.util.Objects.requireNonNull(parentSourceKey, "parentSourceKey");
            java.util.Objects.requireNonNull(parentResultType, "parentResultType");
        }
    }

    /**
     * A child field on a {@link GraphitronType.TableBackedType} parent returning a multi-table
     * {@link GraphitronType.UnionType}. Same shape as {@link InterfaceField}; differs only in
     * the source of the participant set (union member types vs. interface implementers).
     */
    record UnionField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef.PolymorphicReturnType returnType,
        List<ParticipantRef> participants,
        java.util.Map<String, List<JoinStep>> participantJoinPaths,
        SourceKey parentSourceKey,
        GraphitronType.ResultType parentResultType
    ) implements ChildField {
        public UnionField {
            participants = List.copyOf(participants);
            participantJoinPaths = java.util.Map.copyOf(participantJoinPaths);
            java.util.Objects.requireNonNull(parentSourceKey, "parentSourceKey");
            java.util.Objects.requireNonNull(parentResultType, "parentResultType");
        }
    }

    /**
     * A nesting child field whose value is a fragment of the parent's table-bound projection.
     * The fetcher emit is {@code env -> env.getSource()}: graphql-java's traversal walks
     * the nested SDL fields through their own per-field fetchers on the shared parent record.
     */
    record NestingField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef.TableBoundReturnType returnType,
        List<ChildField> nestedFields
    ) implements ChildField {}

    /**
     * A child field whose value is the parent itself, propagated through to a
     * {@code @record}-typed child whose constructor populates from that same parent record.
     * The fetcher emit is {@code env -> env.getSource()}; the constructor wiring runs at the
     * child level through graphql-java's accessor projection.
     */
    record ConstructorField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef returnType
    ) implements ChildField {}

    /**
     * A child field backed by a developer-provided service method ({@code @service}), where the
     * return type is annotated with {@code @table} (source → table-mapped target).
     *
     * <p>Implements {@link TableTargetField} for structural uniformity. The service method replaces
     * direct SQL generation; {@link #filters()}, {@link #orderBy()}, and {@link #pagination()}
     * typically carry empty/None values unless additional filter conditions are present.
     *
     * <p>Parameter binding (including context arguments) is fully encoded in
     * {@link MethodRef#params()} via {@link ParamSource}.
     *
     * @param sourceKey derived from the service method's {@link MethodRef.Param.Sourced}
     *     parameter; {@code null} when the method has no such parameter, which fails validation
     *     (generation runs only post-validation, so it never observes {@code null}).
     * @param loaderRegistration paired with {@code sourceKey}; carries the DataLoader container
     *     (positional list vs mapped set) and dispatch (load vs loadMany) axes the service
     *     return type projects onto.
     */
    record ServiceTableField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef.TableBoundReturnType returnType,
        List<JoinStep> joinPath,
        List<WhereFilter> filters,
        OrderBySpec orderBy,
        PaginationSpec pagination,
        MethodRef method,
        SourceKey sourceKey,
        LoaderRegistration loaderRegistration,
        Optional<ErrorChannel> errorChannel
    ) implements TableTargetField, MethodBackedField, BatchKeyField, WithErrorChannel {
        @Override
        public String rowsMethodName() {
            return "load" + Character.toUpperCase(name().charAt(0)) + name().substring(1);
        }
    }

    /**
     * A child field backed by a developer-provided service method ({@code @service}), where the
     * return type is NOT table-mapped (source → record/scalar target).
     *
     * <p>Parameter binding (including context arguments) is fully encoded in
     * {@link MethodRef#params()} via {@link ParamSource}.
     *
     * @param sourceKey derived from the service method's {@link MethodRef.Param.Sourced}
     *     parameter; {@code null} when the method has no such parameter, which fails validation
     *     (generation runs only post-validation, so it never observes {@code null}).
     * @param loaderRegistration paired with {@code sourceKey}; carries the DataLoader container
     *     (positional list vs mapped set) and dispatch (load vs loadMany) axes the service
     *     return type projects onto.
     */
    record ServiceRecordField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef returnType,
        List<JoinStep> joinPath,
        MethodRef method,
        SourceKey sourceKey,
        LoaderRegistration loaderRegistration,
        Optional<ErrorChannel> errorChannel
    ) implements ChildField, MethodBackedField, BatchKeyField, WithErrorChannel {

        @Override
        public String rowsMethodName() {
            return "load" + Character.toUpperCase(name().charAt(0)) + name().substring(1);
        }

        /**
         * Returns the per-key Java element type this field's loader resolves to (the {@code V}
         * before any list-cardinality wrapping), derived from {@link #returnType()}. Used by
         * the Generator to type {@code DataLoader<K, V>}.
         *
         * <p>Defers to {@link RowsMethodShape#strictPerKeyType} for the schema-determined
         * answer (raw {@code org.jooq.Record} for {@code TableBoundReturnType}, the backing
         * class for {@code ResultReturnType} with non-null {@code fqClassName}, the standard
         * Java type for the five GraphQL spec built-ins via the
         * {@code no.sikt.graphitron.rewrite.ScalarTypeResolver}). When the helper returns
         * {@code null} the fallback is the reflected outer return type on
         * {@link MethodRef#returnType()}, which throws at request time when wrong, surfacing
         * the case to revisit.
         */
        public no.sikt.graphitron.javapoet.TypeName elementType() {
            no.sikt.graphitron.javapoet.TypeName strict = RowsMethodShape.strictPerKeyType(returnType());
            if (strict != null) return strict;
            return method().returnType();
        }
    }

    record RecordTableField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef.TableBoundReturnType returnType,
        List<JoinStep> joinPath,
        List<WhereFilter> filters,
        OrderBySpec orderBy,
        PaginationSpec pagination,
        SourceKey sourceKey,
        LoaderRegistration loaderRegistration
    ) implements TableTargetField, BatchKeyField, ConditionJoinReportable {
        @Override
        public boolean emitsSingleRecordPerKey() {
            // Two structurally distinct triggers fold onto the same router decision in
            // SplitRowsMethodEmitter.buildForRecordTable: (a) single-cardinality fields whose
            // data-fetcher wants `Record` per key (one row per parent), and (b) the
            // loader.loadMany contract whose per-key value is `Record` regardless of field
            // cardinality. The {@link LoaderRegistration.Dispatch} projection is the single
            // source of truth for (b) — TypeFetcherGenerator.buildRecordBasedDataFetcher reads
            // the same predicate to decide its valueType, so the two emit sites cannot drift.
            return !returnType().wrapper().isList()
                || loaderRegistration().dispatch() == LoaderRegistration.Dispatch.LOAD_MANY;
        }
        @Override public Rejection.EmitBlockReason emitBlockReason() {
            return Rejection.EmitBlockReason.RECORD_TABLE_FIELD_CONDITION_JOIN_STEP;
        }
        @Override public String displayLabel() { return "RecordTableField"; }
    }

    record RecordLookupTableField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef.TableBoundReturnType returnType,
        List<JoinStep> joinPath,
        List<WhereFilter> filters,
        OrderBySpec orderBy,
        PaginationSpec pagination,
        SourceKey sourceKey,
        LoaderRegistration loaderRegistration,
        LookupMapping lookupMapping
    ) implements TableTargetField, BatchKeyField, LookupField, ConditionJoinReportable {
        @Override
        public boolean emitsSingleRecordPerKey() {
            // Mirrors RecordTableField: single-cardinality fields fold onto the same
            // single-record-per-key arm as the LOAD_MANY loadMany-contract dispatch. See
            // RecordTableField.emitsSingleRecordPerKey for the single-source-of-truth notes.
            return !returnType().wrapper().isList()
                || loaderRegistration().dispatch() == LoaderRegistration.Dispatch.LOAD_MANY;
        }
        @Override public Rejection.EmitBlockReason emitBlockReason() {
            return Rejection.EmitBlockReason.RECORD_LOOKUP_TABLE_FIELD_CONDITION_JOIN_STEP;
        }
        @Override public String displayLabel() { return "RecordLookupTableField"; }
    }

    /**
     * @param column the resolved parent-table column when the parent is a
     *     {@link GraphitronType.JooqTableRecordType} with a resolvable {@link TableRef} and the
     *     SQL column name maps to a real column; {@code null} otherwise (including for
     *     {@link GraphitronType.JooqRecordType}, {@link GraphitronType.JavaRecordType}, and
     *     {@link GraphitronType.PojoResultType} parents). When non-null, the generator emits a
     *     typed {@code Tables.X.COL} reference; when null, it falls back to
     *     {@code DSL.field("col_name")} or a bean/record accessor depending on the parent.
     * @param accessor the resolved accessor when the parent is a
     *     {@link GraphitronType.JavaRecordType} or a {@link GraphitronType.PojoResultType}
     *     with non-null {@code fqClassName} (i.e. a {@code @record}-Java-backed parent);
     *     {@code null} otherwise. The slot is statically typed
     *     {@link AccessorResolution.Resolved}: classifier-side rejection is routed through
     *     {@link GraphitronField.UnclassifiedField} instead of riding on this slot, so the
     *     emitter consumer never sees a {@link AccessorResolution.Rejected} value here. The
     *     slot stays nullable to carry the parent shapes that don't run reflective resolution
     *     at all (jOOQ-record-backed parents, null-{@code fqClassName} parents).
     */
    record RecordField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef returnType,
        String columnName,
        ColumnRef column,
        AccessorResolution.Resolved accessor
    ) implements ChildField {}

    /**
     * A child field using {@code @externalField} — the developer provides a static method
     * returning a jOOQ {@code Field<X>} that is inlined into the parent's projection at
     * generation time. The method handles the SQL-side computation; runtime wiring uses
     * a {@code ColumnFetcher} keyed on the GraphQL field name.
     *
     * <p>The method signature is:
     * <pre>
     *     Field&lt;X&gt; methodName(&lt;ParentTable&gt; table)
     * </pre>
     * where the table parameter has {@link ParamSource.Table} as its source. Captured by
     * {@link ServiceCatalog#reflectExternalField}.
     */
    record ComputedField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef returnType,
        List<JoinStep> joinPath,
        MethodRef method
    ) implements ChildField, MethodBackedField {}

    /**
     * @param column the resolved parent-table column when the parent is a
     *     {@link GraphitronType.JooqTableRecordType} with a resolvable {@link TableRef} and the
     *     SQL column name maps to a real column; {@code null} otherwise. When non-null, the
     *     generator emits a typed {@code Tables.X.COL} reference; when null, it falls back to
     *     {@code DSL.field("col_name")} or a bean/record accessor depending on the parent.
     * @param accessor the resolved accessor when the parent is a
     *     {@link GraphitronType.JavaRecordType} or a {@link GraphitronType.PojoResultType}
     *     with non-null {@code fqClassName}; {@code null} otherwise (jOOQ-record parents,
     *     null-{@code fqClassName} parents, and {@code @error}-type parents do not run
     *     reflective accessor resolution). See {@link RecordField}'s analogous slot for the
     *     full contract — including that classifier-side rejection routes through
     *     {@link GraphitronField.UnclassifiedField} rather than a {@code Rejected} value
     *     riding on this slot.
     */
    record PropertyField(
        String parentTypeName,
        String name,
        SourceLocation location,
        String columnName,
        ColumnRef column,
        AccessorResolution.Resolved accessor
    ) implements ChildField {}

    /**
     * The {@code errors} field on a payload type. Lift target for the payload-side of a
     * fetcher's typed-error channel: a list-shaped field whose element type is a single
     * {@code @error} type, a union of {@code @error} types, or an interface implemented
     * by {@code @error} types.
     *
     * <p>{@code errorTypes} is the flattened list of mapped {@code @error} types, in source
     * order: one entry for {@code [SomeError]}, the resolved members for {@code [SomeUnion]}
     * or {@code [SomeInterface]}. Polymorphism is a classification-time concern that does
     * not survive into the model; downstream the carrier-side
     * {@link ErrorChannel} consumes this list uniformly.
     *
     * <p>Emission is a passthrough fetcher: at request time the parent's payload object
     * already carries the list (the carrier's try/catch wrapper produced it, or the
     * service-method body did), so the fetcher reads it directly via graphql-java's default
     * {@code PropertyDataFetcher}.
     */
    record ErrorsField(
        String parentTypeName,
        String name,
        SourceLocation location,
        List<GraphitronType.ErrorType> errorTypes
    ) implements ChildField {

        public ErrorsField {
            errorTypes = List.copyOf(errorTypes);
        }
    }
}
