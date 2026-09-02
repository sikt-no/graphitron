package no.sikt.graphitron.rewrite.model;

import graphql.language.SourceLocation;
import no.sikt.graphitron.javapoet.ClassName;

import java.util.List;
import java.util.Optional;
import no.sikt.graphitron.render.CatalogRefs;
import no.sikt.graphitron.model.jooq.ColumnRef;
import no.sikt.graphitron.model.jooq.TableRef;

/**
 * A field on a non-root output type. Source context (table-mapped or result-mapped) is
 * determined by the parent {@link no.sikt.graphitron.rewrite.model.GraphitronType} at generation time.
 */
public sealed interface ChildField extends OutputField
    permits ChildField.ColumnBackedField, ChildField.ColumnBackedReferenceField,
            ChildField.ParticipantColumnReferenceField,
            ChildField.TableTargetField,
            ChildField.InterfaceField, ChildField.UnionField,
            ChildField.BatchedInterfaceField, ChildField.BatchedUnionField,
            ChildField.NestingField,
            ChildField.PivotSpecField, ChildField.PivotSlotField,
            ChildField.ServiceRecordField,
            ChildField.RecordReadField,
            ChildField.RecordCompositeField,
            ChildField.ComputedField,
            ChildField.SingleRecordIdField,
            ChildField.SingleRecordIdFieldFromReturning,
            ChildField.ErrorsField {

    /**
     * The source arrives wrapping this field's {@link #sourceShape()}: {@link Source.OnlyChild}
     * when exactly one source object reaches the fetcher (direct SQL), else the
     * {@link Source.Child} absorbing arm (DataLoader-batched, correct as a one-element batch).
     * The arm is a derived view of a parent-grain fact the caller supplies, not a stored per-leaf
     * component; see {@link OutputField#source(Arrival)} and
     * {@link no.sikt.graphitron.rewrite.GraphitronSchema#sourceOf sourceOf}.
     */
    @Override default Source source(Arrival parentArrival) {
        return parentArrival == Arrival.ONE
            ? new Source.OnlyChild(sourceShape())
            : new Source.Child(sourceShape());
    }

    /**
     * The shape of what arrives at {@code env.getSource()} for this field: a projection of the
     * parent type's backing. A {@code @table}-backed parent puts a table row
     * ({@link SourceShape#Table}); a {@code @service} / DML payload or DTO parent hands back a
     * domain record ({@link SourceShape#Record}). The classifier already projected the parent's
     * backing into this leaf's identity, so the leaf-exhaustive switch is that projection; a new
     * leaf forces a source-shape decision the same way {@link #target()} does.
     *
     * <p>Pinned by {@code SourceShapeProjectionTest}: every classified {@code ChildField} is
     * cross-checked against the parent type's independently-classified backing, so a leaf wired
     * to the wrong arm cannot silently diverge from the projection it claims to be.
     */
    default SourceShape sourceShape() {
        return switch (this) {
            // Catalog-backed (table) parents: the source is a table row.
            case ColumnBackedField ignored -> SourceShape.Table;
            case ColumnBackedReferenceField ignored -> SourceShape.Table;
            case ParticipantColumnReferenceField ignored -> SourceShape.Table;
            case TableField ignored -> SourceShape.Table;

            case TableInterfaceField ignored -> SourceShape.Table;
            case BatchedTableInterfaceField ignored -> SourceShape.Table;
            // Neither service leaf is parent-kind-pure: both are minted on a @table parent and on a
            // class-backed one, so the stored key source is what carries the projection. Its arms
            // are cut on exactly this seam, which makes the derivation total.
            case ServiceTableField f -> f.keySource().sourceShape();
            case ServiceRecordField f -> f.keySource().sourceShape();
            case ComputedField ignored -> SourceShape.Table;
            case NestingField ignored -> SourceShape.Table;
            case InterfaceField ignored -> SourceShape.Table;
            case UnionField ignored -> SourceShape.Table;
            case BatchedInterfaceField ignored -> SourceShape.Table;
            case BatchedUnionField ignored -> SourceShape.Table;
            // @pivot leaves sit on an SQL-backed (@table) parent by classifier guarantee (a
            // record-backed parent is rejected at classify time), so the source is a table row.
            case PivotField ignored -> SourceShape.Table;
            case BatchedPivotField ignored -> SourceShape.Table;
            // A projection slot reads off the pivot subselect's graphitron-built jOOQ Record
            // (or, on the batched path, the scattered per-key Record): a record source.
            case PivotSlotField ignored -> SourceShape.Record;
            // Batched re-query leaves store the parent backing as a component, not a leaf
            // identity; SourceShapeProjectionTest cross-checks the stored fact against the
            // independently-classified parent backing.
            case BatchedTableField f -> f.sourceShape();
            // Record-backed parents (DTO batching, @service / DML payload carriers): the source is a
            // producer-handed domain record.
            case RecordReadField ignored -> SourceShape.Record;
            case RecordCompositeField ignored -> SourceShape.Record;
            case SingleRecordIdField ignored -> SourceShape.Record;
            case SingleRecordIdFieldFromReturning ignored -> SourceShape.Record;
            case ErrorsField ignored -> SourceShape.Record;
        };
    }

    @Override default Target target() {
        return switch (this) {
            // Column projections: no return wrapper on the leaf, so Single(Column).
            case ColumnBackedField ignored -> OutputField.single(new TargetShape.Column());
            case ColumnBackedReferenceField ignored -> OutputField.single(new TargetShape.Column());
            case ParticipantColumnReferenceField ignored -> OutputField.single(new TargetShape.Column());
            // Catalog table reads: wrap(...) keeps the Connection -> Single(Connection) decomposition.
            case TableField f -> OutputField.wrap(f.returnType().wrapper(), new TargetShape.Table());
            case BatchedTableField f -> OutputField.wrap(f.returnType().wrapper(), new TargetShape.Table());
            case TableInterfaceField f -> OutputField.wrap(f.returnType().wrapper(), new TargetShape.Table());
            case BatchedTableInterfaceField f -> OutputField.wrap(f.returnType().wrapper(), new TargetShape.Table());
            case ServiceTableField f -> OutputField.wrap(f.returnType().wrapper(), new TargetShape.Table());
            case NestingField f -> OutputField.wrap(f.returnType().wrapper(), new TargetShape.Table());
            // Java-side shapes: listOrSingle (never Connection, mapping stays flat Record / Field).
            case ServiceRecordField f -> OutputField.listOrSingle(f.returnType().wrapper(), new TargetShape.Record());
            case RecordReadField f -> OutputField.listOrSingle(f.returnType().wrapper(), new TargetShape.Field());
            // Composite carrier data field: the element is a record-backed result type
            // (not a scalar Field, not a @table), distinguishing this leaf from RecordReadField.
            case RecordCompositeField f -> OutputField.listOrSingle(f.returnType().wrapper(), new TargetShape.Record());
            // The pivot projects one graphitron-built record per parent (never a list; the
            // classifier rejects list returns), so the target is Single(Record) on both deliveries.
            case PivotField ignored -> OutputField.single(new TargetShape.Record());
            case BatchedPivotField ignored -> OutputField.single(new TargetShape.Record());
            // A slot is a scalar read off that record: the Java scalar side, like RecordReadField.
            case PivotSlotField ignored -> OutputField.single(new TargetShape.Field());
            // @externalField inlines a jOOQ Field<X> into the parent SELECT; the shape stays Column.
            case ComputedField f -> OutputField.listOrSingle(f.returnType().wrapper(), new TargetShape.Column());
            // Polymorphic children: catalog-bound.
            case InterfaceField f -> OutputField.wrap(f.returnType().wrapper(), new TargetShape.Interface());
            case UnionField f -> OutputField.wrap(f.returnType().wrapper(), new TargetShape.Union());
            case BatchedInterfaceField f -> OutputField.wrap(f.returnType().wrapper(), new TargetShape.Interface());
            case BatchedUnionField f -> OutputField.wrap(f.returnType().wrapper(), new TargetShape.Union());
            // Encoded-PK scalar carriers: Column.
            case SingleRecordIdFieldFromReturning f -> OutputField.listOrSingle(f.returnType().wrapper(), new TargetShape.Column());
            case SingleRecordIdField f -> OutputField.listOrSingle(f.returnType().wrapper(), new TargetShape.Column());
            // The errors field reads an Outcome wrapper arm off env.getSource(); @error element types
            // are object types, so Record (the errors-list wrapper is not modeled on this leaf).
            case ErrorsField ignored -> OutputField.single(new TargetShape.Record());
        };
    }

    /**
     * The single data field on a payload-returning DELETE carrier: an ID-typed scalar encoding
     * the deleted row's primary key. The parent classifies as
     * {@code MutationField.MutationDmlRecordField} (single DELETE) or
     * {@code MutationField.MutationBulkDmlRecordField} (bulk DELETE) returning the PK-only
     * RETURNING rows; this field's fetcher reads PK column(s) off the source {@code Record} and
     * runs them through {@link #encode}.
     *
     * <p>Sibling of the record-sourced {@link BatchedTableField} carrier re-fetch (follow-up
     * SELECT, for INSERT / UPDATE / UPSERT carriers) and {@link SingleRecordIdField} (encoded key
     * off an {@code @service} producer's in-memory record). The three encode genuinely different
     * invariants, not different values of one knob; the sealed split pushes the per-carrier
     * emission story into the type system.
     *
     * <p>Declines {@link TableTargetField} (element is the {@code ID} scalar, not table-bound)
     * and {@link BatchKeyField} (no DataLoader). {@link #encode} carries the resolved per-Node
     * encoder and column shape on the {@link CallSiteCompaction.NodeIdEncodeKeys} slot every
     * other NodeId-encoded projection uses, keeping the element-shape and projection axes split.
     */
    record SingleRecordIdFieldFromReturning(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef.ScalarReturnType returnType,
        CallSiteCompaction.NodeIdEncodeKeys encode
    ) implements ChildField {
        @Override public DomainReturnType domainReturnType() {
            return new DomainReturnType.Plain(STRING_CLASS);
        }
    }

    /**
     * The single data field on an {@code @service} source-record carrier: an ID-typed scalar
     * encoding the producer record's node-key column(s). The parent classifies as
     * {@code MutationField.MutationServiceRecordField}; the {@code @service} method returns the
     * typed jOOQ {@code XRecord} (single) or {@code List<XRecord>} (bulk) verbatim, optionally
     * wrapped in the typed {@code Outcome} when the payload carries an errors field. The fetcher
     * reads the key column(s) straight off the in-memory record(s) and runs them through
     * {@link #encode}. No follow-up SELECT, so the shape is deletion-safe by construction: the
     * record the service returns may already be deleted from the database.
     *
     * <p>Sibling of the record-sourced {@link BatchedTableField} carrier re-fetch (follow-up
     * SELECT off the producer's record) and {@link SingleRecordIdFieldFromReturning} (encoded PK
     * scalar off the DML RETURNING {@code Record}, read by SQL name). It differs from the latter
     * on the source shape, not just the envelope: the source is the developer-declared
     * {@code TableRecord} subclass (read through typed {@code Tables.X.COL} constants), it may
     * arrive wrapped in {@code Outcome}, and the bulk cardinality is a {@code List<XRecord>},
     * not a jOOQ {@code Result}.
     *
     * <p>{@link #table()} is the producer's table, whose typed {@code Tables.X.COL} constants
     * the encode reads. {@link #sourceKey()} carries the node-key columns and
     * {@link SourceKey.Wrap.TableRecord} (the producer's typed record subclass); the compact
     * constructor requires that wrap unconditionally, this leaf being the only
     * {@link #envelope()}-bearing typed-record read. {@link #envelope()} ({@code DIRECT} /
     * {@code OUTCOME_SUCCESS}) is the same axis the table-field sibling's emitter derives as
     * {@link no.sikt.graphitron.rewrite.generators.ParentSourceBinding}; the bulk arrival is the
     * field's own wrapper position ({@code returnType().wrapper().isList()}).
     * {@link #encode} mirrors the compaction slot
     * every other NodeId-encoded projection uses. Declines {@link TableTargetField} (element is
     * the {@code ID} scalar) and {@link BatchKeyField} (no DataLoader).
     */
    record SingleRecordIdField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef.ScalarReturnType returnType,
        TableRef table,
        SourceKey sourceKey,
        SourceEnvelope envelope,
        CallSiteCompaction.NodeIdEncodeKeys encode
    ) implements ChildField {
        public SingleRecordIdField {
            java.util.Objects.requireNonNull(table, "table");
            java.util.Objects.requireNonNull(envelope, "envelope");
            if (!(sourceKey.wrap() instanceof SourceKey.Wrap.TableRecord)) {
                throw new IllegalArgumentException(
                    "SingleRecordIdField requires SourceKey.Wrap.TableRecord (the @service "
                    + "producer returns the typed record subclass); got " + sourceKey.wrap());
            }
        }
        @Override public DomainReturnType domainReturnType() {
            return new DomainReturnType.Plain(STRING_CLASS);
        }
    }

    /**
     * A column-backed output carrier on a table-backed parent: the field's value is produced
     * from {@link #columns()} (arity 1..N) of the parent table, reached without a
     * {@code @reference} path. The value reaches the field through {@link #compaction()}:
     * {@link CallSiteCompaction.Direct} for plain SELECT-term projection (single-column by the
     * constructor invariant below), {@link CallSiteCompaction.NodeIdEncodeKeys} for
     * {@code @nodeId} projections that wrap the column(s) positionally in the per-Node
     * {@code encode<TypeName>} helper. Arity is a column count on this one leaf, not a leaf
     * dimension; consumers branch on {@link #isComposite()}.
     */
    record ColumnBackedField(
        String parentTypeName,
        String name,
        SourceLocation location,
        List<ColumnRef> columns,
        CallSiteCompaction compaction
    ) implements ChildField {
        public ColumnBackedField {
            columns = List.copyOf(columns);
            if (columns.isEmpty()) {
                throw new IllegalArgumentException("ColumnBackedField requires at least one column");
            }
            // Deferred-generalization seam, not a modeling truth: @nodeId is the only
            // multi-column trigger, so a multi-column carrier is always a node-key codec call
            // (and, by corollary, a Direct read is always single-column). A seam to loosen for
            // a plain multi-column projection (arity-N Direct), not a fact to build on.
            if (columns.size() > 1 && !(compaction instanceof CallSiteCompaction.NodeIdEncodeKeys)) {
                throw new IllegalArgumentException(
                    "ColumnBackedField '" + name + "' with arity " + columns.size()
                    + " requires NodeIdEncodeKeys compaction; got " + compaction);
            }
        }
        /**
         * {@code true} when this carrier spans more than one column (a composite node key).
         * Consumers branch here rather than re-evaluating the size predicate.
         */
        public boolean isComposite() { return columns.size() > 1; }
        @Override public DomainReturnType domainReturnType() {
            // NodeIdEncodeKeys compaction encodes the column value(s) to a Base64 String at
            // runtime; the env.getSource() shape downstream is String, not the column class.
            if (compaction instanceof CallSiteCompaction.NodeIdEncodeKeys) {
                return new DomainReturnType.Plain(STRING_CLASS);
            }
            // Direct implies arity 1 (constructor invariant), so the single column's type.
            return new DomainReturnType.Plain(CatalogRefs.columnType(columns.get(0)));
        }
    }

    /**
     * A column-backed output carrier on a table-backed parent reached through a
     * {@code @reference} path. The terminal {@link #columns()} (arity 1..N) live on the joined
     * target table; {@link #compaction()} controls how the value reaches the field:
     * {@link CallSiteCompaction.Direct} for plain projection (single-column by the constructor
     * invariant below), {@link CallSiteCompaction.NodeIdEncodeKeys} for {@code @nodeId}
     * references where the target's keyColumns sit on the joined parent (rooted-at-parent).
     * (Rooted-at-child references, where the FK source columns on the child positionally mirror
     * the keyColumns, collapse to {@link ColumnBackedField} at classification time.) Arity is a
     * column count on this one leaf, not a leaf dimension; consumers branch on
     * {@link #isComposite()}.
     */
    record ColumnBackedReferenceField(
        String parentTypeName,
        String name,
        SourceLocation location,
        List<ColumnRef> columns,
        List<JoinStep> joinPath,
        CallSiteCompaction compaction,
        ParentCorrelation parentCorrelation,
        AliasOwner aliasOwner
    ) implements ChildField, ResultKeyAliasedField {
        public ColumnBackedReferenceField {
            java.util.Objects.requireNonNull(aliasOwner, "aliasOwner");
            columns = List.copyOf(columns);
            if (columns.isEmpty()) {
                throw new IllegalArgumentException("ColumnBackedReferenceField requires at least one column");
            }
            // Same deferred-generalization seam as ColumnBackedField: no plain multi-column
            // reference projection exists, so a multi-column carrier is always a node-key
            // codec call. A seam to loosen for arity-N Direct, not a fact to build on.
            if (columns.size() > 1 && !(compaction instanceof CallSiteCompaction.NodeIdEncodeKeys)) {
                throw new IllegalArgumentException(
                    "ColumnBackedReferenceField '" + name + "' with arity " + columns.size()
                    + " requires NodeIdEncodeKeys compaction; got " + compaction);
            }
            ParentCorrelation.checkCarrierInvariant(parentCorrelation, joinPath, "ColumnBackedReferenceField");
        }
        /**
         * {@code true} when this carrier spans more than one column (a composite node key).
         * Consumers branch here rather than re-evaluating the size predicate.
         */
        public boolean isComposite() { return columns.size() > 1; }
        @Override public DomainReturnType domainReturnType() {
            if (compaction instanceof CallSiteCompaction.NodeIdEncodeKeys) {
                return new DomainReturnType.Plain(STRING_CLASS);
            }
            return new DomainReturnType.Plain(CatalogRefs.columnType(columns.get(0)));
        }
    }

    /**
     * A scalar field on a {@link GraphitronType.TableInterfaceType} participant that
     * resolves to a column on a different table than the participant's own (i.e. via a
     * single-hop {@code @reference}). The interface query projects the column as a correlated
     * subselect gated by the participant's discriminator value, aliased {@link #aliasName}; the
     * per-field DataFetcher reads it back from the result {@code Record} by that alias.
     *
     * <p>Distinct from {@link ColumnBackedReferenceField} (the broader
     * scalar-{@code @reference} story) in <em>who</em> materialises the value, not in what SQL
     * shape: this variant exists for the {@code TableInterfaceType} cross-table
     * participant-field case, where the interface fetcher rather than a per-field method
     * resolves it. Both lower to the same capped subselect.
     */
    record ParticipantColumnReferenceField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ColumnRef column,
        JoinStep.Hop hop,
        String aliasName
    ) implements ChildField {
        public ParticipantColumnReferenceField {
            if (!(hop.on() instanceof On.ColumnPairs)) {
                throw new IllegalArgumentException(
                    "ParticipantColumnReferenceField.hop must be FK-derived (On.ColumnPairs); got "
                    + hop.on());
            }
        }
        /** The FK-derived column pairs of the single cross-table hop. */
        public On.ColumnPairs pairs() { return (On.ColumnPairs) hop.on(); }
        /** The cross table the hop reaches, selected from by the correlated subselect that projects this field; equivalent to {@code hop().targetTable()}. */
        public TableRef targetTable() { return hop.targetTable(); }
        @Override public DomainReturnType domainReturnType() {
            return new DomainReturnType.Plain(CatalogRefs.columnType(column));
        }
    }

    /**
     * A child field that navigates to (or stays at) a table scope and generates SQL.
     *
     * <p>All variants carry, in addition to the core
     * {@link ReturnTypeRef.TableBoundReturnType returnType} and join path:
     * <ul>
     *   <li>{@link #filters()}: ordered WHERE-clause contributions; may be empty.
     *       {@link ConditionFilter} entries are field-level {@code @condition} methods,
     *       {@link GeneratedConditionFilter} entries are Graphitron-generated argument-driven
     *       predicates.</li>
     *   <li>{@link #orderBy()}: authoritative ordering; never null,
     *       {@link OrderBySpec.None} when not applicable or not resolvable.</li>
     *   <li>{@link #pagination()}: Relay pagination arguments; {@code null} when the field has
     *       no pagination arguments.</li>
     *   <li>{@link #lookup()}: the resolved {@code @lookupKey} correspondence; never null,
     *       {@link LookupResolution.None} when the argument surface resolved no lookup. The
     *       polymorphic and service variants answer {@code None} structurally (no storage):
     *       the classifier routes {@code @lookupKey} only onto the table-read leaves.</li>
     * </ul>
     *
     * <p>{@link NestingField} is intentionally excluded: it carries a
     * {@link ReturnTypeRef.TableBoundReturnType} but does not navigate; it inherits the parent's
     * table context unchanged.
     */
    sealed interface TableTargetField extends ChildField, SqlGeneratingField
        permits ChildField.TableField, ChildField.BatchedTableField,
                ChildField.TableInterfaceField, ChildField.BatchedTableInterfaceField,
                ChildField.ServiceTableField {

        ReturnTypeRef.TableBoundReturnType returnType();
        List<JoinStep> joinPath();
        List<WhereFilter> filters();
        OrderBySpec orderBy();
        PaginationSpec pagination();
        LookupResolution lookup();
    }

    record TableField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef.TableBoundReturnType returnType,
        List<JoinStep> joinPath,
        List<WhereFilter> filters,
        OrderBySpec orderBy,
        PaginationSpec pagination,
        LookupResolution lookup,
        ParentCorrelation parentCorrelation,
        AliasOwner aliasOwner
    ) implements TableTargetField, ResultKeyAliasedField {
        public TableField {
            java.util.Objects.requireNonNull(lookup, "lookup");
            java.util.Objects.requireNonNull(aliasOwner, "aliasOwner");
            ParentCorrelation.checkCarrierInvariant(parentCorrelation, joinPath, "TableField");
            // No validator gate re-checks TableField's shape, so the emittable routine-chain
            // set is pinned mechanically here: the check below admits exactly the shape the
            // inline emitter's single CROSS JOIN LATERAL arm renders; other routine chains
            // classify as typed Deferred, never this leaf. @defaultOrder is admitted
            // (OrderBySpec.Fixed against the terminus's real columns, rendered on the
            // terminal alias like any table). The lateral-iff-routine correspondence is
            // JoinStep.Hop's own invariant, not restated here.
            long routineNodes = joinPath.stream()
                .filter(s -> s instanceof JoinStep.Hop h && h.target() instanceof TableExpr.RoutineCall)
                .count();
            if (routineNodes > 0) {
                boolean dayOneSurface = routineNodes == 1
                    && filters.isEmpty()
                    && !(orderBy instanceof OrderBySpec.Argument)
                    && pagination == null
                    && !(returnType.wrapper() instanceof FieldWrapper.Connection);
                if (!dayOneSurface) {
                    throw new IllegalArgumentException(
                        "TableField with a routine-node path must be the inline correlated "
                        + "chain shape (exactly one lateral routine node, no filters/"
                        + "argument-ordering/pagination, non-Connection); other routine chain "
                        + "shapes classify as typed Deferred");
                }
            }
        }
        @Override public DomainReturnType domainReturnType() {
            return new DomainReturnType.Record(returnType.table());
        }
    }

    /**
     * A DataLoader-batched keyed re-query anchor: the field launches its own SELECT keyed on a
     * tuple lifted off the parent's held object, one leaf for both parent backings. The keyed
     * re-query is one primitive {@code f(keys, correlation)}; the source endpoint contributes
     * only how the key tuple is lifted, never visible to the query unit.
     *
     * <p>"Batched" names what distinguishes this leaf from the inline {@link TableField}: the
     * field launches its own keyed, DataLoader-batched re-query anchor (the {@link BatchKeyField}
     * capability sense; distinct from the arrival-cardinality sense in which {@link Source.Child}
     * fetchers are DataLoader-batched, which applies to arrival, not to this leaf axis).
     *
     * @param sourceShape the source gate: {@link SourceShape#Table} for a catalog table-row
     *     parent (the {@code @splitQuery} shape), {@link SourceShape#Record} for a
     *     producer-handed domain-record parent (a {@code @service} / DML payload carrier).
     *     Stored, not derived from {@link #lift()}: {@link KeyLift.FkColumns} is legitimately
     *     carried by both a table-row parent and a jOOQ-record-backed result parent, so the
     *     parent-backing fact cannot be recovered from the lift mechanism.
     * @param lift how the key tuple is lifted off the parent's held object; total on this leaf.
     *     The Table arm always carries {@link KeyLift.FkColumns} (project columns off the held
     *     jOOQ record); the Record arm carries whichever mechanism the parent's backing admits.
     *     The Table emit path is wrap-driven and consumes the lift only through
     *     {@link KeyLift#checkResidueAgreement}, which pins the lift to its derived key residue.
     */
    record BatchedTableField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef.TableBoundReturnType returnType,
        List<JoinStep> joinPath,
        List<WhereFilter> filters,
        OrderBySpec orderBy,
        PaginationSpec pagination,
        SourceShape sourceShape,
        SourceKey sourceKey,
        KeyLift lift,
        LoaderRegistration loaderRegistration,
        LookupResolution lookup,
        ParentCorrelation parentCorrelation
    ) implements TableTargetField, BatchKeyField {
        public BatchedTableField {
            java.util.Objects.requireNonNull(lookup, "lookup");
            // (1) the lift is total and must agree with the key residue it derives.
            java.util.Objects.requireNonNull(lift, "lift");
            KeyLift.checkResidueAgreement(lift, sourceKey, "BatchedTableField");
            // (2) carrier invariant.
            ParentCorrelation.checkCarrierInvariant(parentCorrelation, joinPath, "BatchedTableField");
            java.util.Objects.requireNonNull(sourceShape, "sourceShape");
            if (sourceShape == SourceShape.Table) {
                // (3) a table row is lifted only by column projection; the member-read lifts
                // (Lifter / Accessor / ProducedRecords) are class-backed-parent mechanisms.
                // Checked, not structural: the sealed-gate alternative would mint a second
                // representation of the source-shape axis alongside SourceShape.
                if (!(lift instanceof KeyLift.FkColumns)) {
                    throw new IllegalArgumentException(
                        "BatchedTableField with sourceShape=Table must lift by column projection "
                        + "(KeyLift.FkColumns); a member-read lift (" + lift.getClass().getSimpleName()
                        + ") is a class-backed-parent mechanism");
                }
                // (6) every table-sourced mint dispatches LOAD_ONE. The LOAD_MANY disjunct of
                // emitsSingleRecordPerKey is therefore unreachable on the Table arm by
                // construction, so a mint cannot silently flip a table-sourced field's
                // per-key cardinality.
                if (loaderRegistration.dispatch() != LoaderRegistration.Dispatch.LOAD_ONE) {
                    throw new IllegalArgumentException(
                        "BatchedTableField with sourceShape=Table must dispatch LOAD_ONE; the "
                        + "loadMany contract is an accessor-arity (record-parent) shape");
                }
                // (5) leaf-specific surface pins, mirroring TableField's: a routine-bearing
                // path carries exactly one routine node and none of the surfaces the batched
                // emit does not render for routine chains. A routine-bearing path never
                // carries a keyed lookup (the classifier defers the @routine and @lookupKey
                // pair), so the lookup fold leaves this pin's domain unchanged. Additionally, a lateral-headed split
                // keys the batch on the routine's column-bound inputs, so its sourceKey can
                // never be empty (the classifier rejects the uncorrelated combination as
                // DirectiveConflict). Table-gated: widening to Record would add unaudited
                // checks.
                long routineNodes = joinPath.stream()
                    .filter(s -> s instanceof JoinStep.Hop h && h.target() instanceof TableExpr.RoutineCall)
                    .count();
                if (routineNodes > 0) {
                    boolean dayOneSurface = routineNodes == 1
                        && filters.isEmpty()
                        && !(orderBy instanceof OrderBySpec.Argument)
                        && pagination == null
                        && !(returnType.wrapper() instanceof FieldWrapper.Connection);
                    if (!dayOneSurface) {
                        throw new IllegalArgumentException(
                            "BatchedTableField with a routine-node path must be the batched correlated "
                            + "chain shape (exactly one lateral routine node, no filters/"
                            + "argument-ordering/pagination, non-Connection); other routine chain "
                            + "shapes classify as typed Deferred");
                    }
                }
                if (parentCorrelation instanceof ParentCorrelation.OnLateralArgs
                        && sourceKey.columns().isEmpty()) {
                    throw new IllegalArgumentException(
                        "BatchedTableField with a lateral-headed path must key the batch on the "
                        + "routine's column-bound inputs; an empty sourceKey is the uncorrelated "
                        + "shape, which the classifier rejects as DirectiveConflict");
                }
            } else {
                // (4) no record-parent Connection mint exists for the plain read; the Connection
                // emit arm and its ORDER-BY validator guard stay reachable only from the Table
                // arm, by construction instead of by leaf identity. The keyed-lookup shape is
                // deliberately exempt: a Connection-shaped lookup is an author-reachable schema
                // (rejected by the validator's "lookup fields must not return a connection"
                // check), not an unrepresentable generator state.
                if (lookup instanceof LookupResolution.None
                        && returnType.wrapper() instanceof FieldWrapper.Connection) {
                    throw new IllegalArgumentException(
                        "BatchedTableField with sourceShape=Record cannot be a Connection; no "
                        + "record-parent Connection mint exists");
                }
            }
        }
        @Override
        public boolean emitsSingleRecordPerKey() {
            // One definition for both arms; two structurally distinct triggers fold onto the
            // same router decision in SplitRowsMethodEmitter: (a) single-cardinality fields
            // whose data-fetcher wants `Record` per key (one row per parent), and (b) the
            // loader.loadMany contract whose per-key value is `Record` regardless of field
            // cardinality. The LoaderRegistration.Dispatch projection is the single source of
            // truth for (b): TypeFetcherGenerator's record-based fetcher reads the same
            // predicate to decide its valueType, so the two emit sites cannot drift.
            return !returnType().wrapper().isList()
                || loaderRegistration().dispatch() == LoaderRegistration.Dispatch.LOAD_MANY;
        }
        @Override public DomainReturnType domainReturnType() {
            return new DomainReturnType.Record(returnType.table());
        }
    }

    /**
     * A child field returning a single-table discriminated interface, delivered per parent row.
     * The return type is a {@link GraphitronType.TableInterfaceType}: one shared {@code @table}
     * with a {@code @discriminate} column routing each row to its participant. The statement is
     * its own (the discriminated re-projection cannot fold into the parent's SELECT), but it runs
     * once per parent row, keyed off {@code env.getSource()}.
     *
     * <p>Delivery is leaf identity, the same cardinality rule the multi-table
     * {@link InterfaceField} / {@link BatchedInterfaceField} pair follows: single cardinality
     * fetches per parent and stays here; list cardinality batches through a DataLoader as
     * {@link BatchedTableInterfaceField}. The participant precondition the polymorphic sibling
     * applies has no analogue: a discriminated interface rejects non-table implementors at the
     * parse boundary, so every participant is table-backed by construction.
     */
    record TableInterfaceField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef.TableBoundReturnType returnType,
        ColumnRef discriminatorColumn,
        List<String> knownDiscriminatorValues,
        List<ParticipantRef> participants,
        List<JoinStep> joinPath,
        List<WhereFilter> filters,
        OrderBySpec orderBy,
        PaginationSpec pagination
    ) implements TableTargetField, ParentRowDemand {
        /** Structurally none: the classifier routes {@code @lookupKey} only onto table-read leaves. */
        @Override public LookupResolution lookup() {
            return LookupResolution.None.INSTANCE;
        }
        @Override public DomainReturnType domainReturnType() {
            return new DomainReturnType.Record(returnType.table());
        }
        /**
         * The fetcher correlates the shared child table against the parent row
         * ({@code child.<targetSide> = parentRecord.<sourceSide>}, read by base name off
         * {@code env.getSource()}), so the demand is the single FK-derived hop's source-side
         * columns — the same {@link On.ColumnPairs} slot the correlation emitter reads.
         */
        @Override public List<ColumnRef> parentRowColumns() {
            return ((On.ColumnPairs) ((JoinStep.Hop) joinPath.get(0)).on()).sourceSideColumns();
        }
    }

    /**
     * The DataLoader-batched half of the {@link TableInterfaceField} delivery split: one keyed
     * re-query over the discriminated base table for the whole batch of parents instead of one
     * SELECT per parent row. Minted at list cardinality; single cardinality stays on the
     * unbatched twin.
     *
     * <p>Component semantics are {@link TableInterfaceField}'s. The delta is
     * {@link BatchedTableField}'s, not {@link BatchedInterfaceField}'s, because the key is:
     * the single FK hop's source side, the same columns the twin reports as its parent-row
     * demand, derived through the plain batched child's {@code deriveSplitQuerySource}. The
     * polymorphic sibling keys on the parent's <em>primary key</em> (each participant holds its
     * own FK back to the parent) and rejects an empty PK for that reason; neither applies here.
     *
     * <p>Declines {@link ParentRowDemand} deliberately: the batched arm of
     * {@code ProjectionCommands.tableTargetContribution} precedes its demand arm and reads
     * {@link #sourceKey()} through {@link BatchKeyField}, so the demand accessor would never be
     * reached, and the two answer the same column list anyway. The projection membership census
     * takes either capability, so {@link BatchKeyField} alone satisfies it.
     *
     * <p>The compact constructor pins the batch shape: non-single cardinality (the mint gate),
     * column-projection key lift off the held parent row, and the {@code @splitQuery} loader
     * contract ({@link LoaderRegistration.Container#POSITIONAL_LIST} +
     * {@link LoaderRegistration.Dispatch#LOAD_ONE}), a catalog-FK key being one key per parent
     * row.
     */
    record BatchedTableInterfaceField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef.TableBoundReturnType returnType,
        ColumnRef discriminatorColumn,
        List<String> knownDiscriminatorValues,
        List<ParticipantRef> participants,
        List<JoinStep> joinPath,
        List<WhereFilter> filters,
        OrderBySpec orderBy,
        PaginationSpec pagination,
        SourceKey sourceKey,
        KeyLift lift,
        LoaderRegistration loaderRegistration,
        ParentCorrelation parentCorrelation
    ) implements TableTargetField, BatchKeyField {
        public BatchedTableInterfaceField {
            knownDiscriminatorValues = List.copyOf(knownDiscriminatorValues);
            participants = List.copyOf(participants);
            java.util.Objects.requireNonNull(lift, "lift");
            java.util.Objects.requireNonNull(loaderRegistration, "loaderRegistration");
            KeyLift.checkResidueAgreement(lift, sourceKey, "BatchedTableInterfaceField");
            ParentCorrelation.checkCarrierInvariant(
                parentCorrelation, joinPath, "BatchedTableInterfaceField");
            if (!returnType.wrapper().isList()) {
                throw new IllegalArgumentException("BatchedTableInterfaceField '" + name
                    + "': the batched delivery is the non-single cardinality arm; a single "
                    + "return classifies as the inline twin");
            }
            if (!(lift instanceof KeyLift.FkColumns)) {
                throw new IllegalArgumentException("BatchedTableInterfaceField '" + name
                    + "': the parent is table-backed by classifier guarantee, so the key is "
                    + "lifted by column projection (KeyLift.FkColumns); a member-read lift ("
                    + lift.getClass().getSimpleName() + ") is a class-backed-parent mechanism");
            }
            if (loaderRegistration.container() != LoaderRegistration.Container.POSITIONAL_LIST) {
                throw new IllegalArgumentException("BatchedTableInterfaceField '" + name
                    + "': the discriminated batched delivery registers a positional-list "
                    + "DataLoader; no mapped-container source declaration exists on this path");
            }
            if (loaderRegistration.dispatch() != LoaderRegistration.Dispatch.LOAD_ONE) {
                throw new IllegalArgumentException("BatchedTableInterfaceField '" + name
                    + "': a catalog-FK key is one key per parent row, so the dispatch is "
                    + "LOAD_ONE; the loadMany contract is an accessor-arity (record-parent) shape");
            }
        }
        /** Structurally none: the classifier routes {@code @lookupKey} only onto table-read leaves. */
        @Override public LookupResolution lookup() {
            return LookupResolution.None.INSTANCE;
        }
        @Override public DomainReturnType domainReturnType() {
            return new DomainReturnType.Record(returnType.table());
        }
    }

    /**
     * A single-cardinality child field returning a multi-table
     * {@link GraphitronType.InterfaceType}: the inline per-parent delivery. Carries the resolved
     * participants list plus the per-participant {@code joinPath} (one auto-discovered FK chain
     * from the parent table to each participant's table) so the multi-table polymorphic emitter
     * can emit a per-branch WHERE in the stage-1 narrow UNION ALL.
     *
     * <p>Delivery is leaf identity, mirroring the {@link PivotField} / {@link BatchedPivotField}
     * split: this inline arm fetches per parent (no DataLoader) and deliberately does not
     * implement {@link BatchKeyField}; the list and connection cardinalities with at least one
     * table-bound participant classify as {@link BatchedInterfaceField}, which does. The
     * degenerate all-unbound participant set stays here at any cardinality (nothing to batch;
     * the fetcher hands back the empty payload inline), which is why this leaf reads its
     * cardinality off the wrapper rather than pinning single.
     *
     * <p>{@code participantJoinPaths} is keyed by participant typename: exactly one entry per
     * {@link ParticipantRef.TableBound} participant. {@link ParticipantRef.Unbound} participants
     * are absent from the map; they contribute no SQL branch. Each value is a
     * {@link ParticipantCorrelation} carrying the resolved parent-to-participant correlation:
     * the classifier decided the shape is supported once, and the emitter cannot represent an
     * unsupported one. Every value is a {@link ParticipantCorrelation.KeyTupleWhere} (single-hop
     * FK, auto-discovered or {@code @referenceFor}-disambiguated) or a
     * {@link ParticipantCorrelation.JoinedCorrelation} (multi-hop FK chain or condition
     * correlation).
     *
     * <p>{@code sourceKey} and {@code parentResultType} are the parent-object key-extraction
     * strategy and shape, threaded into {@code GeneratorUtils.buildRecordParentKeyExtraction}.
     * The classifier produces a catalog-FK {@link KeyLift.FkColumns} key for a table-backed
     * parent and an accessor-derived {@link KeyLift.Accessor} key for a class-backed / record
     * parent (the hub discovered by {@code FieldBuilder.derivePolymorphicHubSource}).
     * {@link KeyLift#checkResidueAgreement} pins the residue {@code sourceKey} to the lift
     * arm at construction.
     *
     * <p>{@code parentKeyOwnerTable} is the parent/hub table owning
     * {@code sourceKey.columns()} (the parent's {@code @table} on the table-backed arm,
     * the accessor-discovered hub on the record-backed arm), resolved at the same classification
     * site as the key. The batched rows methods read
     * {@code Tables.<OWNER>.<COL>.getDataType()} off it so converter-backed parent keys bind
     * through the column's registered jOOQ Converter at the DB type.
     */
    record InterfaceField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef.PolymorphicReturnType returnType,
        List<ParticipantRef> participants,
        java.util.Map<String, ParticipantCorrelation> participantJoinPaths,
        SourceKey sourceKey,
        KeyLift parentKeyLift,
        TableRef parentKeyOwnerTable,
        GraphitronType.ResultType parentResultType
    ) implements ChildField, ParentRowDemand {
        public InterfaceField {
            participants = List.copyOf(participants);
            participantJoinPaths = java.util.Map.copyOf(participantJoinPaths);
            // Validator and emitter both read sourceKey / parentResultType
            // unconditionally; carry the non-null contract in the type system rather than
            // by reviewer-tracked correspondence.
            java.util.Objects.requireNonNull(sourceKey, "sourceKey");
            java.util.Objects.requireNonNull(parentKeyLift, "parentKeyLift");
            java.util.Objects.requireNonNull(parentKeyOwnerTable, "parentKeyOwnerTable");
            java.util.Objects.requireNonNull(parentResultType, "parentResultType");
            KeyLift.checkResidueAgreement(parentKeyLift, sourceKey, "InterfaceField");
        }
        /**
         * The value handed down is one of the participant types' rows, chosen at run time by the
         * polymorphic dispatch, so no single Java class names what arrives:
         * {@link DomainReturnType.NoClaim}. An interface SDL return is filtered out of the
         * conflict reduction's grouping in any case, so the answer's only surface is the
         * rejection rendering.
         */
        @Override public DomainReturnType domainReturnType() {
            return new DomainReturnType.NoClaim();
        }
        @Override public List<ColumnRef> parentRowColumns() {
            return ParentRowDemand.polymorphicParentRowColumns(
                returnType.wrapper().isList(), participantJoinPaths, sourceKey);
        }
    }

    /**
     * A single-cardinality child field returning a multi-table
     * {@link GraphitronType.UnionType}: the inline per-parent delivery. Same shape as
     * {@link InterfaceField}; differs only in the source of the participant set (union member
     * types vs. interface implementers). The list and connection cardinalities with a
     * table-bound participant classify as {@link BatchedUnionField}; the degenerate all-unbound
     * set stays here at any cardinality.
     */
    record UnionField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef.PolymorphicReturnType returnType,
        List<ParticipantRef> participants,
        java.util.Map<String, ParticipantCorrelation> participantJoinPaths,
        SourceKey sourceKey,
        KeyLift parentKeyLift,
        TableRef parentKeyOwnerTable,
        GraphitronType.ResultType parentResultType
    ) implements ChildField, ParentRowDemand {
        public UnionField {
            participants = List.copyOf(participants);
            participantJoinPaths = java.util.Map.copyOf(participantJoinPaths);
            java.util.Objects.requireNonNull(sourceKey, "sourceKey");
            java.util.Objects.requireNonNull(parentKeyLift, "parentKeyLift");
            java.util.Objects.requireNonNull(parentKeyOwnerTable, "parentKeyOwnerTable");
            java.util.Objects.requireNonNull(parentResultType, "parentResultType");
            KeyLift.checkResidueAgreement(parentKeyLift, sourceKey, "UnionField");
        }
        /** See {@link InterfaceField#domainReturnType()}: the same run-time-chosen participant row. */
        @Override public DomainReturnType domainReturnType() {
            return new DomainReturnType.NoClaim();
        }
        @Override public List<ColumnRef> parentRowColumns() {
            return ParentRowDemand.polymorphicParentRowColumns(
                returnType.wrapper().isList(), participantJoinPaths, sourceKey);
        }
    }

    /**
     * A list- or connection-cardinality child field returning a multi-table
     * {@link GraphitronType.InterfaceType}: the DataLoader-batched delivery, the batched half of
     * the {@link InterfaceField} delivery split. Component semantics are {@link InterfaceField}'s;
     * this leaf additionally carries the {@link LoaderRegistration} the batched fetcher registers
     * with, so the loader container and the {@code load}-vs-{@code loadMany} dispatch are
     * classifier decisions read as data, not emitter re-derivations from the lift's arity.
     *
     * <p>The compact constructor pins {@link LoaderRegistration.Container#POSITIONAL_LIST}: the
     * polymorphic classifier never mints a mapped container (there is no {@code Set}-shaped
     * source declaration on this path), and the emitter's single
     * {@code DataLoaderFactory.newDataLoader} call assumes it without a guard because this
     * entailment makes the mapped cell unrepresentable.
     */
    record BatchedInterfaceField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef.PolymorphicReturnType returnType,
        List<ParticipantRef> participants,
        java.util.Map<String, ParticipantCorrelation> participantJoinPaths,
        SourceKey sourceKey,
        KeyLift parentKeyLift,
        TableRef parentKeyOwnerTable,
        GraphitronType.ResultType parentResultType,
        LoaderRegistration loaderRegistration
    ) implements ChildField, BatchKeyField, ParentRowDemand {
        public BatchedInterfaceField {
            participants = List.copyOf(participants);
            participantJoinPaths = java.util.Map.copyOf(participantJoinPaths);
            java.util.Objects.requireNonNull(sourceKey, "sourceKey");
            java.util.Objects.requireNonNull(parentKeyLift, "parentKeyLift");
            java.util.Objects.requireNonNull(parentKeyOwnerTable, "parentKeyOwnerTable");
            java.util.Objects.requireNonNull(parentResultType, "parentResultType");
            java.util.Objects.requireNonNull(loaderRegistration, "loaderRegistration");
            KeyLift.checkResidueAgreement(parentKeyLift, sourceKey, "BatchedInterfaceField");
            if (loaderRegistration.container() != LoaderRegistration.Container.POSITIONAL_LIST) {
                throw new IllegalStateException("BatchedInterfaceField '" + name
                    + "': the polymorphic batched delivery registers a positional-list "
                    + "DataLoader; a mapped container cannot be minted on this path");
            }
            if (participants.stream().noneMatch(p -> p instanceof ParticipantRef.TableBound)) {
                throw new IllegalStateException("BatchedInterfaceField '" + name
                    + "': the batched delivery requires a table-bound participant; the "
                    + "all-unbound set classifies as the inline leaf");
            }
        }
        /** See {@link InterfaceField#domainReturnType()}: the same run-time-chosen participant row. */
        @Override public DomainReturnType domainReturnType() {
            return new DomainReturnType.NoClaim();
        }
        @Override public List<ColumnRef> parentRowColumns() {
            return ParentRowDemand.polymorphicParentRowColumns(
                returnType.wrapper().isList(), participantJoinPaths, sourceKey);
        }
        /**
         * {@code false} unconditionally, stated rather than inherited: the polymorphic family
         * is outside both consumer sites the base contract names (it inlines its own scatter
         * and never renders through the launcher relation), and its per-key value is never a
         * single record — a bucket on the list arm, one {@code ConnectionResult} on the
         * connection arm, a bucket flattened by the {@code loadMany} tail on the accessor-many
         * arm.
         */
        @Override public boolean emitsSingleRecordPerKey() {
            return false;
        }
    }

    /**
     * A list- or connection-cardinality child field returning a multi-table
     * {@link GraphitronType.UnionType}: the DataLoader-batched delivery, the batched half of the
     * {@link UnionField} delivery split. Same shape as {@link BatchedInterfaceField}; differs
     * only in the source of the participant set.
     */
    record BatchedUnionField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef.PolymorphicReturnType returnType,
        List<ParticipantRef> participants,
        java.util.Map<String, ParticipantCorrelation> participantJoinPaths,
        SourceKey sourceKey,
        KeyLift parentKeyLift,
        TableRef parentKeyOwnerTable,
        GraphitronType.ResultType parentResultType,
        LoaderRegistration loaderRegistration
    ) implements ChildField, BatchKeyField, ParentRowDemand {
        public BatchedUnionField {
            participants = List.copyOf(participants);
            participantJoinPaths = java.util.Map.copyOf(participantJoinPaths);
            java.util.Objects.requireNonNull(sourceKey, "sourceKey");
            java.util.Objects.requireNonNull(parentKeyLift, "parentKeyLift");
            java.util.Objects.requireNonNull(parentKeyOwnerTable, "parentKeyOwnerTable");
            java.util.Objects.requireNonNull(parentResultType, "parentResultType");
            java.util.Objects.requireNonNull(loaderRegistration, "loaderRegistration");
            KeyLift.checkResidueAgreement(parentKeyLift, sourceKey, "BatchedUnionField");
            if (loaderRegistration.container() != LoaderRegistration.Container.POSITIONAL_LIST) {
                throw new IllegalStateException("BatchedUnionField '" + name
                    + "': the polymorphic batched delivery registers a positional-list "
                    + "DataLoader; a mapped container cannot be minted on this path");
            }
            if (participants.stream().noneMatch(p -> p instanceof ParticipantRef.TableBound)) {
                throw new IllegalStateException("BatchedUnionField '" + name
                    + "': the batched delivery requires a table-bound participant; the "
                    + "all-unbound set classifies as the inline leaf");
            }
        }
        /** See {@link InterfaceField#domainReturnType()}: the same run-time-chosen participant row. */
        @Override public DomainReturnType domainReturnType() {
            return new DomainReturnType.NoClaim();
        }
        @Override public List<ColumnRef> parentRowColumns() {
            return ParentRowDemand.polymorphicParentRowColumns(
                returnType.wrapper().isList(), participantJoinPaths, sourceKey);
        }
        /** See {@link BatchedInterfaceField#emitsSingleRecordPerKey()}: same fact, same reasons. */
        @Override public boolean emitsSingleRecordPerKey() {
            return false;
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
    ) implements ChildField {
        /**
         * NestingField is a pass-through: the fetcher emits {@code env -> env.getSource()}, so
         * the children of the nested SDL type receive the parent's record verbatim. The parent's
         * table varies across nesting-reuse sites (the same nested SDL type can be reached from
         * multiple {@code @table} parents; see {@code GraphitronSchemaBuilderTest.
         * SHARED_NESTED_TYPE_ACROSS_PARENTS_COMPATIBLE}); the children read by column name on
         * the generic jOOQ {@code Record} interface, not by typed {@code Tables.X.COL}. The
         * domain-return identity is therefore the generic {@code org.jooq.Record}, which any
         * nesting reuse-site agrees on.
         */
        @Override public DomainReturnType domainReturnType() {
            return new DomainReturnType.Plain(ClassName.get("org.jooq", "Record"));
        }
    }

    /**
     * The two {@code @pivot} delivery leaves, an intermediate seal in the
     * {@link TableTargetField} mould: the aggregate operation's parameters ride the carried
     * {@link OperationMember.Pivot} member row (the coordinate's member set reads it by
     * identity), and the target and join halves ride {@link #spec()}, so a switch arm on this
     * seal keeps the full field surface without a cast back to the concrete leaf.
     */
    sealed interface PivotSpecField extends ChildField
        permits ChildField.PivotField, ChildField.BatchedPivotField {

        OperationMember.Pivot pivot();
        PivotSpec spec();
    }

    /**
     * The inline {@code @pivot} leaf: a discriminator-keyed aggregate projection folded into the
     * parent query as a correlated aggregate subselect (one round-trip, no DataLoader, no
     * {@code GROUP BY}; the aggregate over the correlated set collapses to one row on its own).
     * One projection record exists per parent, always: a correlated aggregate over an empty set
     * still returns one row of nulls, never a null record; which slots are null is the only
     * data-dependent part. The aggregate's parameters ride {@link #pivot()}, the target and join
     * halves {@link #spec()}; the return type is a plain directive-free output type registered
     * as an ordinary {@link GraphitronType.NestingType}.
     *
     * <p>Mirrors the {@link TableField} / {@link BatchedTableField} delivery split rather than
     * fusing both deliveries into one nullable-bag leaf: this inline arm deliberately does not
     * implement {@link BatchKeyField}; {@code @splitQuery} classifies the sibling
     * {@link BatchedPivotField} instead.
     */
    record PivotField(
        String parentTypeName,
        String name,
        SourceLocation location,
        OperationMember.Pivot pivot,
        PivotSpec spec,
        AliasOwner aliasOwner
    ) implements PivotSpecField, ResultKeyAliasedField {
        public PivotField {
            java.util.Objects.requireNonNull(aliasOwner, "aliasOwner");
            PivotSpec.checkMemberAgreement(pivot, spec, "PivotField");
        }
        /**
         * The projection subselect returns a graphitron-built generic jOOQ {@code Record} whose
         * fields are the slot aggregates; the slot fetchers read it by name, exactly as nesting
         * children read a shared parent record. Same identity as {@link NestingField}, so a
         * projection type reached by both a pivot edge and a plain nesting edge agrees on its
         * domain return type.
         */
        @Override public DomainReturnType domainReturnType() {
            return new DomainReturnType.Plain(ClassName.get("org.jooq", "Record"));
        }
    }

    /**
     * The batched ({@code @splitQuery}) {@code @pivot} leaf: the same projection as
     * {@link PivotField}, delivered through the existing DataLoader seam (a keyed rows method with
     * a {@code VALUES} parent-input table, {@code GROUP BY __idx__} over the batch, scattered
     * single-per-key). The pivot's batch query joins the attribute table <em>from</em> the
     * parent-input table key-preservingly (a left join, the one deviation from the table shape's
     * inner join) so every batch key produces a group and a row-less parent scatters to a record
     * of null slots, preserving the one-record-per-parent invariant inline delivery satisfies for
     * free. That key preservation must hold over the entire parent-input → terminus chain, which
     * is why the {@link PivotSpec} pins the path to a single FK hop.
     */
    record BatchedPivotField(
        String parentTypeName,
        String name,
        SourceLocation location,
        OperationMember.Pivot pivot,
        PivotSpec spec,
        SourceKey sourceKey,
        KeyLift lift,
        LoaderRegistration loaderRegistration,
        ParentCorrelation parentCorrelation
    ) implements PivotSpecField, BatchKeyField {
        public BatchedPivotField {
            PivotSpec.checkMemberAgreement(pivot, spec, "BatchedPivotField");
            java.util.Objects.requireNonNull(lift, "lift");
            KeyLift.checkResidueAgreement(lift, sourceKey, "BatchedPivotField");
            ParentCorrelation.checkCarrierInvariant(parentCorrelation, spec.joinPath(), "BatchedPivotField");
            // The parent is SQL-backed by classifier guarantee, so the key is always lifted by
            // column projection and dispatched LOAD_ONE: the same Table-arm invariants
            // BatchedTableField pins.
            if (!(lift instanceof KeyLift.FkColumns)) {
                throw new IllegalArgumentException(
                    "BatchedPivotField must lift by column projection (KeyLift.FkColumns); a "
                    + "member-read lift (" + lift.getClass().getSimpleName()
                    + ") is a class-backed-parent mechanism and @pivot rejects class-backed parents");
            }
            if (loaderRegistration.dispatch() != LoaderRegistration.Dispatch.LOAD_ONE) {
                throw new IllegalArgumentException(
                    "BatchedPivotField must dispatch LOAD_ONE; the loadMany contract is an "
                    + "accessor-arity (record-parent) shape");
            }
        }
        /** One projection record per key, always: the pivot invariant, not a cardinality fold. */
        @Override public boolean emitsSingleRecordPerKey() {
            return true;
        }
        /** See {@link PivotField#domainReturnType()}: the same generic-record identity. */
        @Override public DomainReturnType domainReturnType() {
            return new DomainReturnType.Plain(ClassName.get("org.jooq", "Record"));
        }
    }

    /**
     * One projection slot of a {@code @pivot} field's return type, carrying exactly one fact: its
     * {@link #readName()}, the projected column alias derived from the slot's SDL name. The
     * discriminator token never reaches the slot (it is consumed only where {@link PivotSpec}
     * builds the subselect), so the same plain projection type is reusable across pivots that
     * resolve different tokens. The emitted read is the same by-name generic-{@code Record} read
     * nesting children emit, which is what lets one registered fetcher per slot coordinate serve
     * both the pivot subselect's {@code Record} and a compatible nesting parent's record.
     *
     * <p>A {@link RecordReadField} reuse was considered and rejected: that leaf's meaning is a
     * read off a {@link GraphitronType.ResultType}-classified parent whose {@link ValueLocator}
     * arm the classifier resolved, neither of which a slot has. The dedicated leaf forks on
     * identity in the sealed dispatch instead.
     */
    record PivotSlotField(
        String parentTypeName,
        String name,
        SourceLocation location,
        String readName
    ) implements ChildField {
        public PivotSlotField {
            java.util.Objects.requireNonNull(readName, "readName");
        }
        /**
         * A slot value is whatever the projected aggregate yields for its column, read by name
         * off the generic {@code Record}; nothing in the model names its Java class, so the
         * answer is {@link DomainReturnType.NoClaim}.
         */
        @Override public DomainReturnType domainReturnType() {
            return new DomainReturnType.NoClaim();
        }
    }

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
     *     parameter, non-null: a child {@code @service} whose method declares no such parameter is
     *     rejected at classify time, so the leaf is never constructed without a key.
     * @param keySource where the emitted fetcher binds the record the key columns are read off. The
     *     component {@link ChildField#sourceShape()} derives this leaf's answer from: the leaf is
     *     minted on both a {@code @table} and a class-backed parent, so leaf identity cannot carry
     *     that fact.
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
        ServiceKeySource keySource,
        LoaderRegistration loaderRegistration,
        Optional<ErrorChannel.RouterDispatched> errorChannel
    ) implements TableTargetField, MethodBackedField, BatchKeyField, WithErrorChannel {
        public ServiceTableField {
            java.util.Objects.requireNonNull(sourceKey, "sourceKey");
            java.util.Objects.requireNonNull(keySource, "keySource");
        }
        /** Structurally none: the classifier routes {@code @lookupKey} only onto table-read leaves. */
        @Override public LookupResolution lookup() {
            return LookupResolution.None.INSTANCE;
        }
        /**
         * Although the service method returns the typed {@code XRecord} (or
         * {@code List<XRecord>}) per the service-producer-strict-return contract, the typed
         * record IS-A jOOQ {@code Record} and the @table-bound child datafetchers read columns
         * by name through the generic {@code Record} interface. The consumer-level identity is
         * therefore {@code Record(table)}, agreeing with the SQL-emit table-bound producers
         * ({@link TableField}, {@link BatchedTableField}, etc.) so a {@code @table}-bound SDL
         * type reached by both a service and an SQL-emit producer does not surface as a
         * spurious conflict.
         */
        @Override public DomainReturnType domainReturnType() {
            return new DomainReturnType.Record(returnType.table());
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
     *     parameter, non-null: a child {@code @service} whose method declares no such parameter is
     *     rejected at classify time, so the leaf is never constructed without a key.
     * @param keySource where the emitted fetcher binds the record the key columns are read off. The
     *     component {@link ChildField#sourceShape()} derives this leaf's answer from; see the
     *     sibling {@link ServiceTableField}.
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
        ServiceKeySource keySource,
        LoaderRegistration loaderRegistration,
        Optional<ErrorChannel.RouterDispatched> errorChannel
    ) implements ChildField, MethodBackedField, BatchKeyField, WithErrorChannel {
        public ServiceRecordField {
            java.util.Objects.requireNonNull(sourceKey, "sourceKey");
            java.util.Objects.requireNonNull(keySource, "keySource");
        }

        /**
         * The per-key Java element type this field's loader resolves to (the {@code V} before
         * any list-cardinality wrapping), derived from {@link #returnType()}. Used by the
         * Generator to type {@code DataLoader<K, V>}.
         *
         * <p>Defers to {@link RowsMethodShape#strictPerKeyType} for the schema-determined
         * answer. When that returns {@code null} (a non-built-in scalar leaf: an enum, or an
         * unregistered custom scalar) the per-key {@code V} is peeled off the reflected outer
         * return type on {@link MethodRef#returnType()} via
         * {@link RowsMethodShape#perKeyFromOuter}, so the rows method's declared type is the
         * flat {@code Map<K, V>} matching the service contract rather than a doubly-nested
         * {@code Map<K, Map<K, V>>}. Falls back to the whole reflected type only when the shape
         * isn't peelable, which {@code ServiceDirectiveResolver.validateChildServiceReturnType}
         * rejects at classify time.
         */
        public no.sikt.graphitron.javapoet.TypeName elementType() {
            var grounded = groundedElementType();
            return grounded != null ? grounded : method().returnType();
        }

        /**
         * The per-key {@code V} where something grounds it, and {@code null} where nothing does.
         * One derivation, two null policies: {@link #elementType()} falls back to the whole
         * reflected type so a missed shape validation surfaces as the same request-time failure
         * as before, while {@link #domainReturnType()} answers
         * {@link DomainReturnType.NoClaim} rather than claiming a class no child fetcher sees.
         */
        private no.sikt.graphitron.javapoet.TypeName groundedElementType() {
            no.sikt.graphitron.javapoet.TypeName strict = RowsMethodShape.strictPerKeyType(returnType());
            if (strict != null) return strict;
            // Non-built-in scalar leaf: the service method already declares the outer
            // Map<K, V> / List<V>, so peel V off it; handing back the whole reflected type
            // would let outerRowsReturnType wrap it once more into a Map<K, Map<K, V>> that
            // does not compile. Other null-perKey returns (a backing-less ResultReturnType)
            // ground nothing.
            if (returnType() instanceof ReturnTypeRef.ScalarReturnType) {
                // loaderRegistration().container() is the field-level projection of the service
                // method's Sourced param container (FieldBuilder.buildServiceLoaderRegistration
                // stores sourced.container() verbatim), the same axis
                // ServiceDirectiveResolver.validateChildServiceReturnType peels with, so emitter
                // and validator can't disagree on isMapped.
                boolean isMapped = loaderRegistration() != null
                    && loaderRegistration().container() == LoaderRegistration.Container.MAPPED_SET;
                return RowsMethodShape.perKeyFromOuter(method().returnType(), returnType(), isMapped);
            }
            return null;
        }
        /**
         * The per-key element the emitted {@code DataLoader<K, V>} resolves to, not the rows
         * method's outer return: a batched leaf's {@code Map<K, V>} never reaches
         * {@code env.getSource()} (the loader resolves it to one {@code V} per key), so a claim
         * peeled off the reflected outer would name a class no child fetcher ever sees.
         *
         * <p>Derived from {@link #returnType()} rather than from the reflected return, the same
         * way {@link #elementType()} derives the {@code V} the loader is typed with, so the claim
         * and the loader cannot disagree about what {@code V} is:
         * <ul>
         *   <li>{@link ReturnTypeRef.ResultReturnType}:
         *       {@link DomainReturnType#claimForResultReturn}, whose grounded-class branch is
         *       exactly {@link RowsMethodShape#strictPerKeyType}'s answer for this arm.</li>
         *   <li>{@link ReturnTypeRef.ScalarReturnType}: the per-key element as
         *       {@link #elementType()} derives it, and {@link DomainReturnType.NoClaim} where
         *       neither the schema nor the reflected outer grounds one. The arm is not a synonym
         *       for "SDL scalar": a type that classified as a nesting or connection type falls
         *       through to it and is an SDL Object type the conflict reduction does group, which
         *       is why this derives a real element rather than answering no-claim wholesale.</li>
         * </ul>
         */
        @Override public DomainReturnType domainReturnType() {
            return switch (returnType()) {
                case ReturnTypeRef.ResultReturnType r -> DomainReturnType.claimForResultReturn(r);
                case ReturnTypeRef.ScalarReturnType ignored -> {
                    var element = groundedElementType();
                    yield element == null
                        ? new DomainReturnType.NoClaim()
                        : new DomainReturnType.Plain(element);
                }
                // A @table-typed return routes to the ServiceTableField sibling and a polymorphic
                // one is deferred at classify, so neither arm reaches this leaf.
                case ReturnTypeRef.TableBoundReturnType ignored -> new DomainReturnType.NoClaim();
                case ReturnTypeRef.PolymorphicReturnType ignored -> new DomainReturnType.NoClaim();
            };
        }
    }

    /**
     * A value read off a record-backed parent's in-memory source object: the scalar / enum /
     * non-table-object field on a {@code @service} or DML payload carrier, DTO, or
     * {@code @error} type. The read mechanism lives on the sealed {@link #locator()}; read
     * sites switch on its arm identity and consult the parent {@link GraphitronType.ResultType}
     * only for the cast target. Arm/parent-shape compatibility is a validate-time rule (see
     * {@link ValueLocator}), which is what keeps the emitter's per-arm casts guard-free.
     *
     * <p>{@code returnType} covers both classification triggers: the scalar/enum SDL return
     * carries a {@link ReturnTypeRef.ScalarReturnType}, the non-table object return the
     * resolved {@link ReturnTypeRef.ResultReturnType}. {@link #target()} mirrors the field's
     * own SDL wrapper unconditionally ({@code listOrSingle(returnType.wrapper(), Field)}), so
     * a list-shaped scalar such as an {@code @error} type's {@code path: [String!]!} models
     * {@code List} like every other wrapper-carrying leaf.
     *
     * <p>{@link #compaction()} is the wire direction, the same slot the column-backed carriers
     * carry: {@link CallSiteCompaction.Direct} where the read's value is the field's value, and
     * {@link CallSiteCompaction.NodeIdEncodeKeys} where the field carries
     * {@code @nodeId(typeName:)} and the read's value is a node key to encode. The read is one
     * value, so the encode arm here is single-key by construction; a composite-key node type at a
     * read coordinate is refused at classify time, and the constructor invariant below is the
     * backstop rather than the author-facing statement. Which of the four locator arms produced
     * the value is independent of the direction, which is why one slot serves all four.
     */
    record RecordReadField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef returnType,
        ValueLocator locator,
        CallSiteCompaction compaction
    ) implements ChildField {
        public RecordReadField {
            java.util.Objects.requireNonNull(returnType, "returnType");
            java.util.Objects.requireNonNull(locator, "locator");
            java.util.Objects.requireNonNull(compaction, "compaction");
            if (compaction instanceof CallSiteCompaction.NodeIdEncodeKeys enc
                    && enc.encodeMethod().paramSignature().size() != 1) {
                throw new IllegalArgumentException(
                    "RecordReadField '" + name + "' encodes from a read, which yields one value, so"
                    + " the node key must be a single column; got arity "
                    + enc.encodeMethod().paramSignature().size());
            }
        }
        /**
         * Forks on {@link #returnType()}, not on the locator arm:
         * <ul>
         *   <li>{@link ReturnTypeRef.ResultReturnType}:
         *       {@link DomainReturnType#claimForResultReturn}, following the consumer-level
         *       identity precedent {@link ServiceTableField#domainReturnType()} sets. Derived from
         *       the leaf's return type rather than from the reflected accessor handle, so a
         *       covariant accessor declaration or a raw generic return cannot mint a false
         *       conflict; the two are held equal upstream, where
         *       {@code FieldBuilder.resolveRecordAccessor} passes the SDL type's bound class as
         *       {@code ClassAccessorResolver}'s expected return and a component whose accessor
         *       does not yield it never reaches this leaf.</li>
         *   <li>{@link ReturnTypeRef.ScalarReturnType}: the column type under a
         *       {@link ValueLocator.TypedColumn} locator, and {@link DomainReturnType.NoClaim}
         *       under the others, where no fact in the model names what the read yields.</li>
         * </ul>
         *
         * <p>An encode compaction answers ahead of both arms, on the column-backed carriers' own
         * terms: what reaches the consumer is the encoded {@code String}, whatever the read
         * yielded, so the claim is about the wire value and not about the locator.
         */
        @Override public DomainReturnType domainReturnType() {
            if (compaction instanceof CallSiteCompaction.NodeIdEncodeKeys) {
                return new DomainReturnType.Plain(STRING_CLASS);
            }
            return switch (returnType) {
                case ReturnTypeRef.ResultReturnType r -> DomainReturnType.claimForResultReturn(r);
                case ReturnTypeRef.ScalarReturnType ignored -> switch (locator) {
                    case ValueLocator.TypedColumn tc -> new DomainReturnType.Plain(CatalogRefs.columnType(tc.column()));
                    case ValueLocator.JavaAccessor ignored2 -> new DomainReturnType.NoClaim();
                    case ValueLocator.ByName ignored2 -> new DomainReturnType.NoClaim();
                    case ValueLocator.DefaultRead ignored2 -> new DomainReturnType.NoClaim();
                };
                // A @table-typed return classifies as a table-read leaf and a polymorphic one as
                // an interface / union leaf; neither arm reaches this one.
                case ReturnTypeRef.TableBoundReturnType ignored -> new DomainReturnType.NoClaim();
                case ReturnTypeRef.PolymorphicReturnType ignored -> new DomainReturnType.NoClaim();
            };
        }
    }

    /**
     * The single data field on an {@code @service} record-composite carrier payload. The
     * {@code @service} method returns a consumer-authored composite (a POJO bundling several
     * jOOQ records, e.g. one {@code FilmRecord} plus a {@code List<ActorRecord>}) as
     * {@code List<Composite>} (list arrival) or one {@code Composite} (single arrival),
     * optionally wrapped in the typed {@code Outcome} when the payload carries an errors field.
     * This data field is a <em>source passthrough projection</em>: its fetcher reads the
     * producer's in-memory record(s) straight off {@code env.getSource()} (narrowing
     * {@code Outcome.Success.value()} under {@link SourceEnvelope#OUTCOME_SUCCESS}) and returns
     * them verbatim, with no DataLoader, no re-fetch, and no SQL. graphql-java then maps each
     * composite element onto the data field's element SDL type, whose {@code @field}-mapped
     * {@code @table} children resolve through the record-backed accessor path off the composite.
     *
     * <p>Sibling of the record-sourced {@link BatchedTableField} arm (a PK-keyed re-fetch) and
     * {@link RecordReadField} (a scalar / accessor read): its {@link #target()} is
     * {@code listOrSingle(Record)} (the element is a record-backed result type, not a
     * {@code @table} and not a scalar {@code Field}) and its {@link #domainReturnType()} is the
     * per-element composite class, there being no single table to name. Carries no
     * {@code SourceKey} / {@code LoaderRegistration} (a passthrough, not a batched load) and no
     * {@code MethodRef} (the producing {@code @service} call is on the parent mutation field,
     * not on this data field, distinguishing it from {@link ServiceRecordField}).
     *
     * <p>{@code returnType} is the data field's element result type
     * ({@link ReturnTypeRef.ResultReturnType}), carrying the arrival cardinality on its
     * {@code wrapper()} and the composite's binary class name on its {@code fqClassName()}.
     * {@code envelope} is the source-envelope fork ({@code DIRECT} for a bare producer return,
     * {@code OUTCOME_SUCCESS} for an errors-bearing carrier returning the typed {@code Outcome}
     * wrapper), carried on the leaf rather than recomputed at emit because the passthrough
     * fetcher and the {@code OutcomeChildArmSwitch} validator both read it. The sibling errors
     * field rides {@link Transport.WrapperArm}.
     */
    record RecordCompositeField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef.ResultReturnType returnType,
        SourceEnvelope envelope
    ) implements ChildField {
        public RecordCompositeField {
            java.util.Objects.requireNonNull(returnType, "returnType");
            java.util.Objects.requireNonNull(envelope, "envelope");
            if (returnType.fqClassName() == null) {
                throw new IllegalArgumentException(
                    "RecordCompositeField requires a non-null returnType.fqClassName() (the composite "
                    + "class the producer returns); the element result type must have bound on the "
                    + "result axis before this leaf is built");
            }
        }
        /**
         * The per-element composite class, minted through
         * {@link DomainReturnType#claimForBacking} rather than spelled here: a nested composite
         * class spelled {@code Outer$Nested} by {@code ClassName.bestGuess} and
         * {@code Outer.Nested} by the shared mint are unequal {@link ClassName}s, which is the
         * false-conflict defect the single mint exists to prevent. The compact constructor pins
         * the non-null backing class, so the claim is never a no-claim here.
         */
        @Override public DomainReturnType domainReturnType() {
            return DomainReturnType.claimForBacking(returnType);
        }
    }

    /**
     * A child field using {@code @externalField}: the developer provides a static method
     * returning a jOOQ {@code Field<X>} that is inlined into the parent's projection at
     * generation time. The method handles the SQL-side computation; runtime wiring uses
     * a {@code LightFetcher}-wrapped read of the aliased column, keyed on the GraphQL field name.
     *
     * <p>The method signature is:
     * <pre>
     *     Field&lt;X&gt; methodName(&lt;ParentTable&gt; table)
     * </pre>
     * where the table parameter has {@link ParamSource.Table} as its source. Captured by
     * {@code ServiceCatalog.reflectExternalField}.
     */
    record ComputedField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef returnType,
        List<JoinStep> joinPath,
        MethodRef method,
        AliasOwner aliasOwner
    ) implements ChildField, MethodBackedField, ResultKeyAliasedField {
        public ComputedField {
            java.util.Objects.requireNonNull(aliasOwner, "aliasOwner");
        }
        @Override public DomainReturnType domainReturnType() {
            return new DomainReturnType.Plain(OutputField.peelToClassName(method.returnType()));
        }
    }

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
     * <p>{@code transport} discriminates at emit time between the two ways graphql-java can
     * surface the errors list to the field's data fetcher: a property accessor off the
     * parent payload (the catch-arm built a developer payload class and slotted the list in)
     * or {@link graphql.execution.DataFetcherResult#getLocalContext()} (the catch arm shipped
     * {@code data(null).localContext(errors).build()}). The arm is selected by
     * {@code FieldBuilder.liftToErrorsField} at classify time with the parent carrier's
     * resolved {@link ErrorChannel} in scope, then printed by
     * {@code FetcherEmitter.dataFetcherValue} via an exhaustive switch on
     * {@link Transport}. The {@link Transport.LocalContext} arm fires when the parent payload has
     * a producer binding ({@code DmlEmitted} or {@code ServiceEmitted}), routed through
     * {@code FieldBuilder.transportForParent}; the {@link Transport.PayloadAccessor} arm fires
     * for plain class-backed parents whose errors-shaped field is a developer-owned slot.
     */
    record ErrorsField(
        String parentTypeName,
        String name,
        SourceLocation location,
        List<GraphitronType.ErrorType> errorTypes,
        Transport transport
    ) implements ChildField {

        public ErrorsField {
            errorTypes = List.copyOf(errorTypes);
            if (transport == null) {
                throw new IllegalArgumentException("ErrorsField: transport must be non-null");
            }
        }
        /**
         * The list this field hands down is heterogeneous: one developer exception class per
         * mapped {@code @error} type, chosen at run time. No single class names what arrives, so
         * the honest answer is {@link DomainReturnType.NoClaim}. What the children of those
         * types can read is enforced elsewhere, by {@code HandlerAccessorCheck}, which holds
         * every handler's source class to the same declared field set.
         */
        @Override public DomainReturnType domainReturnType() {
            return new DomainReturnType.NoClaim();
        }
    }

    /**
     * Where the errors-field data fetcher reads its value from at request time. Sealed so
     * {@code FetcherEmitter.dataFetcherValue}'s {@code ErrorsField} arm dispatches with
     * compiler-enforced exhaustiveness; an added arm forces every consumer site to
     * acknowledge it.
     *
     * <ul>
     *   <li>{@link PayloadAccessor} : the parent payload exposes the errors list as a
     *       property reachable via graphql-java's default {@code PropertyDataFetcher}
     *       (record accessor, JavaBean getter, or public field).</li>
     *   <li>{@link LocalContext} : the fetcher reads from
     *       {@code env.getLocalContext()}. Pairs with an
     *       {@link ErrorChannel.LocalContext} catch arm that ships
     *       {@code DataFetcherResult.<R>newResult().data(null).localContext(errors).build()};
     *       the parent payload is bypassed entirely.</li>
     *   <li>{@link WrapperArm} : the errors ride an {@code Outcome.ErrorList} arm on
     *       {@code env.getSource()}, paired with an {@link ErrorChannel.Mapped} catch arm.</li>
     * </ul>
     */
    sealed interface Transport {
        /** The errors list rides on the parent payload's errors-named property. */
        record PayloadAccessor() implements Transport {}

        /**
         * The errors list rides on graphql-java's {@code DataFetcherResult.localContext} slot.
         * The parent's data field's fetcher must short-circuit on a null source so the
         * catch path renders {@code data: null, errors: [...]} at the SDL level.
         */
        record LocalContext() implements Transport {}

        /**
         * The errors list rides on an {@code Outcome.ErrorList} arm carried as the
         * {@code env.getSource()} of an in-scope {@code @service} outcome
         * type. The errors-field fetcher reads {@code ErrorList.errors} off the non-null
         * {@code Outcome} source; sibling data fields project {@code Success.value} (rendering null
         * on the error arm). Pairs with an {@link ErrorChannel.Mapped} catch arm. Every immediate
         * child of such an outcome type must arm-switch (pinned by
         * {@code GraphitronSchemaValidator.validateOutcomeChildArmSwitch}).
         */
        record WrapperArm() implements Transport {}
    }
}
