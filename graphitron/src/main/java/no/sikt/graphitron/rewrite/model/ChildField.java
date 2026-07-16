package no.sikt.graphitron.rewrite.model;

import graphql.language.SourceLocation;
import no.sikt.graphitron.javapoet.ClassName;

import java.util.List;
import java.util.Optional;

/**
 * A field on a non-root output type. Source context (table-mapped or result-mapped) is
 * determined by the parent {@link no.sikt.graphitron.rewrite.model.GraphitronType} at generation time.
 */
public sealed interface ChildField extends OutputField
    permits ChildField.ColumnField, ChildField.ColumnReferenceField,
            ChildField.ParticipantColumnReferenceField,
            ChildField.CompositeColumnField, ChildField.CompositeColumnReferenceField,
            ChildField.TableTargetField,
            ChildField.TableMethodField,
            ChildField.RecordTableMethodField,
            ChildField.InterfaceField, ChildField.UnionField,
            ChildField.NestingField,
            ChildField.ServiceRecordField,
            ChildField.RecordField,
            ChildField.RecordCompositeField,
            ChildField.ComputedField, ChildField.PropertyField,
            ChildField.SingleRecordIdField,
            ChildField.SingleRecordIdFieldFromReturning,
            ChildField.ErrorsField {

    /**
     * Every {@code ChildField} leaf is on a non-root parent type, so the source arrives wrapping this
     * field's {@link #sourceShape()}. The true ancestor-product arrival is folded in here: when the parent
     * type's {@code parentArrival} is {@link Arrival#ONE} exactly one source object reaches this field's
     * fetcher, so the arm is {@link Source.OnlyChild} (single arrival, direct SQL); otherwise it is the
     * {@link Source.Child} absorbing arm (arrival {@code Many}, DataLoader-batched, always correct as a
     * one-element batch). The arm is a derived view of a parent-grain fact the caller supplies (every
     * field on one parent folds the same way), not a stored per-leaf component; see
     * {@link OutputField#source(Arrival)} and {@link GraphitronSchema#sourceOf}.
     */
    @Override default Source source(Arrival parentArrival) {
        return parentArrival == Arrival.ONE
            ? new Source.OnlyChild(sourceShape())
            : new Source.Child(sourceShape());
    }

    /**
 * The shape of what arrives at {@code env.getSource()} for this field. A projection of
     * the parent type's backing: a {@code @table}-backed (catalog) parent puts a table row
     * ({@link SourceShape#Table}); a {@code @service} / DML payload or DTO parent hands back a domain
     * record ({@link SourceShape#Record}). The classifier already projected the parent's backing into
     * this leaf's identity (the {@code BatchedTableField} stored gate, {@code RecordField} vs
     * {@code ColumnField}, the {@code SingleRecord*} / {@code Errors} payload fields), so the
     * leaf-exhaustive switch is that projection; a new leaf forces a source-shape decision the same
     * way {@link #operation()} / {@link #target()} do.
     *
     * <p>The invariant is pinned by {@code SourceShapeProjectionTest}: for every classified
     * {@code ChildField} in the classification corpus, this leaf-derived value is cross-checked
     * against the parent GraphQL type's independently-classified backing (a {@code TableBackedType}
     * yields {@link SourceShape#Table}, any other backing {@link SourceShape#Record}), so a leaf
     * wired to the wrong arm cannot silently diverge from the projection it claims to be.
     */
    default SourceShape sourceShape() {
        return switch (this) {
            // Catalog-backed (table) parents: the source is a table row.
            case ColumnField ignored -> SourceShape.Table;
            case ColumnReferenceField ignored -> SourceShape.Table;
            case ParticipantColumnReferenceField ignored -> SourceShape.Table;
            case CompositeColumnField ignored -> SourceShape.Table;
            case CompositeColumnReferenceField ignored -> SourceShape.Table;
            case TableField ignored -> SourceShape.Table;
            case LookupTableField ignored -> SourceShape.Table;

            case TableInterfaceField ignored -> SourceShape.Table;
            case TableMethodField ignored -> SourceShape.Table;
            case ServiceTableField ignored -> SourceShape.Table;
            case ServiceRecordField ignored -> SourceShape.Table;
            case ComputedField ignored -> SourceShape.Table;
            case NestingField ignored -> SourceShape.Table;
            case InterfaceField ignored -> SourceShape.Table;
            case UnionField ignored -> SourceShape.Table;
            // The merged batched re-query leaf: the parent backing is a stored component,
            // not a leaf identity — read it. SourceShapeProjectionTest's cross-check against the
            // independently-classified parent backing keeps holding and gets stronger here (a
            // stored fact checked against a walk, not a tautology of leaf identity).
            case BatchedTableField f -> f.sourceShape();
            case BatchedLookupTableField f -> f.sourceShape();
            // Record-backed parents (DTO batching, @service / DML payload carriers): the source is a
            // producer-handed domain record.
            case RecordTableMethodField ignored -> SourceShape.Record;
            case RecordField ignored -> SourceShape.Record;
            case RecordCompositeField ignored -> SourceShape.Record;
            case PropertyField ignored -> SourceShape.Record;
            case SingleRecordIdField ignored -> SourceShape.Record;
            case SingleRecordIdFieldFromReturning ignored -> SourceShape.Record;
            case ErrorsField ignored -> SourceShape.Record;
        };
    }

    @Override default Operation operation() {
        return switch (this) {
            // Catalog column / scalar projections off an already-arrived source: bare reads, no
            // filter surface. The column-ness is a target shape fact, not an operation fact.
            case ColumnField ignored -> OutputField.bareFetch();
            case ColumnReferenceField ignored -> OutputField.bareFetch();
            case ParticipantColumnReferenceField ignored -> OutputField.bareFetch();
            case CompositeColumnField ignored -> OutputField.bareFetch();
            case CompositeColumnReferenceField ignored -> OutputField.bareFetch();
            // Table targets carrying a filter surface: Paginate when the wrapper is a connection, else Fetch.
            case TableField f -> OutputField.readOperation(f.returnType(), f.filters(), f.orderBy(), f.pagination());
            case BatchedTableField f -> OutputField.readOperation(f.returnType(), f.filters(), f.orderBy(), f.pagination());
            case TableInterfaceField f -> OutputField.readOperation(f.returnType(), f.filters(), f.orderBy(), f.pagination());
            // Method-backed / polymorphic table reads carry no field-level filter surface.
            case TableMethodField f -> OutputField.readOperation(f.returnType(), List.of(), new OrderBySpec.None(), null);
            case RecordTableMethodField f -> OutputField.readOperation(f.returnType(), List.of(), new OrderBySpec.None(), null);
            case InterfaceField f -> OutputField.readOperation(f.returnType(), List.of(), new OrderBySpec.None(), null);
            case UnionField f -> OutputField.readOperation(f.returnType(), List.of(), new OrderBySpec.None(), null);
            // Lookup-keyed reads.
            case LookupTableField f -> new Operation.Lookup(f.lookupMapping());
            case BatchedLookupTableField f -> new Operation.Lookup(f.lookupMapping());
            // Developer @service calls (reflected MethodRef carrier).
            case ServiceTableField f -> OutputField.serviceCall(f.method());
            case ServiceRecordField f -> OutputField.serviceCall(f.method());
            // Record / passthrough scalar reads.
            case RecordField ignored -> OutputField.bareFetch();
            // The @service record-composite carrier's data field: a bare source-passthrough
            // projection of the producer's in-memory composite record(s), no filter surface.
            case RecordCompositeField ignored -> OutputField.bareFetch();
            case PropertyField ignored -> OutputField.bareFetch();
            case ComputedField ignored -> OutputField.bareFetch();
            // Structural nesting (asserted, not derived from an absent join-path).
            case NestingField ignored -> new Operation.Nest();
            // Encoded-PK scalar carriers and the errors field: bare reads off the source record.
            case SingleRecordIdFieldFromReturning ignored -> OutputField.bareFetch();
            case SingleRecordIdField ignored -> OutputField.bareFetch();
            case ErrorsField ignored -> OutputField.bareFetch();
        };
    }

    @Override default Target target() {
        return switch (this) {
            // Column projections: no return wrapper on the leaf, so Single(Column).
            case ColumnField ignored -> OutputField.single(new TargetShape.Column());
            case ColumnReferenceField ignored -> OutputField.single(new TargetShape.Column());
            case ParticipantColumnReferenceField ignored -> OutputField.single(new TargetShape.Column());
            case CompositeColumnField ignored -> OutputField.single(new TargetShape.Column());
            case CompositeColumnReferenceField ignored -> OutputField.single(new TargetShape.Column());
            // Catalog table reads: wrap(...) keeps the Connection -> Single(Connection) decomposition.
            case TableField f -> OutputField.wrap(f.returnType().wrapper(), new TargetShape.Table());
            case BatchedTableField f -> OutputField.wrap(f.returnType().wrapper(), new TargetShape.Table());
            case LookupTableField f -> OutputField.wrap(f.returnType().wrapper(), new TargetShape.Table());
            case BatchedLookupTableField f -> OutputField.wrap(f.returnType().wrapper(), new TargetShape.Table());
            case TableInterfaceField f -> OutputField.wrap(f.returnType().wrapper(), new TargetShape.Table());
            case TableMethodField f -> OutputField.wrap(f.returnType().wrapper(), new TargetShape.Table());
            case RecordTableMethodField f -> OutputField.wrap(f.returnType().wrapper(), new TargetShape.Table());
            case ServiceTableField f -> OutputField.wrap(f.returnType().wrapper(), new TargetShape.Table());
            case NestingField f -> OutputField.wrap(f.returnType().wrapper(), new TargetShape.Table());
            // Java-side shapes: listOrSingle (never Connection, mapping stays flat Record / Field).
            case ServiceRecordField f -> OutputField.listOrSingle(f.returnType().wrapper(), new TargetShape.Record());
            case RecordField f -> OutputField.listOrSingle(f.returnType().wrapper(), new TargetShape.Field());
            // The composite carrier's data field projects the producer's record composite(s):
            // a Record-shaped, list-or-single target (the element is a record-backed result type,
            // not a scalar Field and not a @table), distinguishing this leaf from RecordField.
            case RecordCompositeField f -> OutputField.listOrSingle(f.returnType().wrapper(), new TargetShape.Record());
            case PropertyField ignored -> OutputField.single(new TargetShape.Field());
            // @externalField inlines a jOOQ Field<X> into the parent SELECT; the shape stays Column.
            case ComputedField f -> OutputField.listOrSingle(f.returnType().wrapper(), new TargetShape.Column());
            // Polymorphic children: catalog-bound (shape payload modeled-but-unpopulated this slice).
            case InterfaceField f -> OutputField.wrap(f.returnType().wrapper(), new TargetShape.Interface());
            case UnionField f -> OutputField.wrap(f.returnType().wrapper(), new TargetShape.Union());
            // Encoded-PK scalar carriers: Column.
            case SingleRecordIdFieldFromReturning f -> OutputField.listOrSingle(f.returnType().wrapper(), new TargetShape.Column());
            case SingleRecordIdField f -> OutputField.listOrSingle(f.returnType().wrapper(), new TargetShape.Column());
            // The errors field reads an Outcome wrapper arm off env.getSource(); @error element types
            // are object types, so Record (the errors-list wrapper is not modeled on this leaf).
            case ErrorsField ignored -> OutputField.single(new TargetShape.Record());
        };
    }

    /**
 * The single data field on a payload-returning DELETE carrier whose data field is an
     * ID-typed scalar encoding the deleted row's primary key. The parent classifies as
     * {@code MutationField.MutationDmlRecordField} (single DELETE) or
     * {@code MutationField.MutationBulkDmlRecordField} (bulk DELETE) returning the PK-only
     * RETURNING rows; this data field's fetcher reads PK column(s) off the source {@code Record}
     * and runs them through {@link #encode} to produce the encoded NodeId.
     *
     * <p>Sibling of the record-sourced {@code BatchedTableField} carrier re-fetch ("follow-up SELECT,"
     * load-bearing for INSERT / UPDATE / UPSERT carriers) and {@link SingleRecordIdField} ("encoded
     * key off an {@code @service} producer's in-memory record"). The three encode genuinely
     * different invariants — follow-up SELECT vs no-follow-up-encoded-PK-off-RETURNING vs
     * no-follow-up-encoded-key-off-record — not different values of one knob; the sealed split
     * pushes the per-carrier emission story into the type system.
     *
     * <p>Declines {@link TableTargetField} (element is the {@code ID} scalar, not a table-bound
     * type) and {@link BatchKeyField} (no DataLoader). The {@link #encode} compaction carries
     * the resolved per-Node encoder and the column shape, mirroring the
     * {@link CallSiteCompaction.NodeIdEncodeKeys} slot every other NodeId-encoded projection
     * uses; the encoder lives on the compaction slot rather than being fused into the element
     * shape so the "what's the element shape" and "how to project it" axes stay split.
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
 * The single data field on an {@code @service} source-record carrier whose data
     * field is an ID-typed scalar encoding the producer record's node-key column(s). The
     * parent classifies as {@code MutationField.MutationServiceRecordField}; the {@code @service}
     * method returns the typed jOOQ {@code XRecord} (single) or {@code List<XRecord>} (bulk)
     * verbatim, optionally wrapped in the typed {@code Outcome} when the payload carries an
     * errors field. This data field's fetcher reads the key column(s) straight off the
     * in-memory record(s) and runs them through {@link #encode} — no follow-up SELECT, so the
     * shape is deletion-safe by construction (the opptak {@code fjernSakTagg} /
     * {@code fjernSakTagger} delete-then-echo mutations are the driving case: the record the
     * service returns is already deleted from the database).
     *
     * <p>Sibling of the record-sourced {@code BatchedTableField} carrier re-fetch ("follow-up SELECT off the
     * producer's record") and {@link SingleRecordIdFieldFromReturning} ("encoded PK scalar off the DML
     * RETURNING {@code Record}, read by SQL name"). It differs
     * from {@code SingleRecordIdFieldFromReturning} on the source shape, not just the envelope:
     * the source is the developer-declared {@code TableRecord} subclass (read through typed
     * {@code Tables.X.COL} constants), it may arrive wrapped in {@code Outcome}, and the bulk
     * cardinality is a {@code List<XRecord>}, not a jOOQ {@code Result}.
     *
     * <p>{@link #table()} is the producer's table, whose typed {@code Tables.X.COL} constants
     * the encode reads (first-class on the leaf, no longer a {@code SourceKey} slot).
     * The {@link #sourceKey()} carries the node-key columns the encode reads and
     * {@link SourceKey.Wrap.TableRecord} (the producer's typed record subclass — required by
     * the compact constructor, which is the named join site for the retired
     * {@code ResultRowWalk(OUTCOME_SUCCESS)} ⇒ {@code TableRecord} invariant: this leaf is the
     * only {@link #envelope()}-bearing typed-record read, and it requires the typed wrap
     * unconditionally). {@link #envelope()} ({@code DIRECT} / {@code OUTCOME_SUCCESS}) is the
     * same axis the table-field sibling's emitter derives at the type level as
     * {@code sourceIsOutcome}; the bulk arrival is the field's own wrapper position
     * ({@code returnType().wrapper().isList()}). The {@link #encode} compaction mirrors the
     * slot every other NodeId-encoded projection uses. Declines {@link TableTargetField}
     * (element is the {@code ID} scalar) and {@link BatchKeyField} (no DataLoader).
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
    ) implements ChildField {
        @Override public DomainReturnType domainReturnType() {
            // NodeIdEncodeKeys compaction encodes the column value to a Base64 String at runtime;
            // the env.getSource() shape downstream is String, not the underlying column class.
            if (compaction instanceof CallSiteCompaction.NodeIdEncodeKeys) {
                return new DomainReturnType.Plain(STRING_CLASS);
            }
            return new DomainReturnType.Plain(column.columnType());
        }
    }

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
        CallSiteCompaction compaction,
        ParentCorrelation parentCorrelation
    ) implements ChildField {
        public ColumnReferenceField {
            ParentCorrelation.checkCarrierInvariant(parentCorrelation, joinPath, "ColumnReferenceField");
        }
        @Override public DomainReturnType domainReturnType() {
            if (compaction instanceof CallSiteCompaction.NodeIdEncodeKeys) {
                return new DomainReturnType.Plain(STRING_CLASS);
            }
            return new DomainReturnType.Plain(column.columnType());
        }
    }

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
        /** The cross table joined to project this field — equivalent to {@code hop().targetTable()}. */
        public TableRef targetTable() { return hop.targetTable(); }
        @Override public DomainReturnType domainReturnType() {
            return new DomainReturnType.Plain(column.columnType());
        }
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
        @Override public DomainReturnType domainReturnType() {
            return new DomainReturnType.Plain(STRING_CLASS);
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
        @Override public DomainReturnType domainReturnType() {
            return new DomainReturnType.Plain(STRING_CLASS);
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
        permits ChildField.TableField, ChildField.BatchedTableField,
                ChildField.LookupTableField, ChildField.BatchedLookupTableField,
                ChildField.TableInterfaceField,
                ChildField.ServiceTableField {

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
        PaginationSpec pagination,
        ParentCorrelation parentCorrelation
    ) implements TableTargetField {
        public TableField {
            ParentCorrelation.checkCarrierInvariant(parentCorrelation, joinPath, "TableField");
            // TableField is an implemented leaf, so no validator gate re-checks its
            // shape — the emittable routine-chain set is pinned mechanically here rather than
            // by classifier prose. A routine-bearing path carries exactly one routine node
            // (chains with more land as typed Deferred, never on this leaf; the emitter's
            // single CROSS JOIN LATERAL arm covers exactly this set) and none of the surfaces
            // the inline emit does not render for routine chains: no filters, no
            // argument-driven ordering, no pagination, non-Connection. @defaultOrder is
            // admitted (OrderBySpec.Fixed against the terminus's real columns, rendered on
            // the terminal alias like any table). The lateral-iff-routine correspondence is
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
                        + "shapes classify as typed Deferred (R435)");
                }
            }
        }
        @Override public DomainReturnType domainReturnType() {
            return new DomainReturnType.Record(returnType.table());
        }
    }

    /**
     * A DataLoader-batched keyed re-query anchor: the field launches its own SELECT keyed on a
     * tuple lifted off the parent's held object, one leaf for both parent backings (a full merge,
     * with the key laundered across both backings). The keyed re-query is one primitive
     * {@code f(keys, correlation)}; the source endpoint contributes only how the key tuple is
     * lifted, never visible to the query unit.
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
     * @param lift how the key tuple is lifted off the parent's held object — total on this leaf.
     *     The Table arm always carries {@link KeyLift.FkColumns} (project columns off the held
     *     jOOQ record; its derived wrap is the {@code Row} residue the split mint stores); the
     *     Record arm carries whichever mechanism the parent's backing admits. The Table arm's
     *     lift is consumed only by {@link KeyLift#checkResidueAgreement} today (the Table emit
     *     path stays wrap-driven); storing it consistent-by-construction is deliberate
     *     provisioning for the eventual unified fetcher.
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
        ParentCorrelation parentCorrelation
    ) implements TableTargetField, BatchKeyField {
        public BatchedTableField {
            // (1) the lift is total and must agree with the key residue it derives.
            java.util.Objects.requireNonNull(lift, "lift");
            KeyLift.checkResidueAgreement(lift, sourceKey, "BatchedTableField");
            // (2) carrier invariant, unchanged from both pre-merge leaves.
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
                // (6) every table-sourced mint dispatches LOAD_ONE (deriveSplitQuerySource's
                // former prose guarantee, now structural). This is what keeps the unified
                // emitsSingleRecordPerKey formula behavior-identical for the Table arm: the
                // LOAD_MANY disjunct is unreachable here by construction, so a future mint
                // cannot silently flip a table-sourced field's per-key cardinality.
                if (loaderRegistration.dispatch() != LoaderRegistration.Dispatch.LOAD_ONE) {
                    throw new IllegalArgumentException(
                        "BatchedTableField with sourceShape=Table must dispatch LOAD_ONE; the "
                        + "loadMany contract is an accessor-arity (record-parent) shape");
                }
                // (5) leaf-specific surface pins, mirroring TableField's: a routine-bearing
                // path carries exactly one routine node and none of the surfaces the batched emit
                // does not render for routine chains. Additionally, a lateral-headed split keys
                // the batch on the routine's column-bound inputs, so its sourceKey can never be
                // empty (the classifier rejects the uncorrelated combination as
                // DirectiveConflict). Table-gated: whether these should hold universally is an
                // in-flight refinement question, not assumed — widening to Record would add
                // unaudited checks.
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
                            + "shapes classify as typed Deferred (R435)");
                    }
                }
                if (parentCorrelation instanceof ParentCorrelation.OnLateralArgs
                        && sourceKey.columns().isEmpty()) {
                    throw new IllegalArgumentException(
                        "BatchedTableField with a lateral-headed path must key the batch on the "
                        + "routine's column-bound inputs; an empty sourceKey is the uncorrelated "
                        + "shape, which the classifier rejects as DirectiveConflict (R435)");
                }
            } else {
                // (4) no record-parent Connection mint exists; the Connection emit arm and its
                // ORDER-BY validator guard stay reachable only from the Table arm, by
                // construction instead of by leaf identity.
                if (returnType.wrapper() instanceof FieldWrapper.Connection) {
                    throw new IllegalArgumentException(
                        "BatchedTableField with sourceShape=Record cannot be a Connection; no "
                        + "record-parent Connection mint exists (R432)");
                }
            }
        }
        @Override
        public boolean emitsSingleRecordPerKey() {
            // One definition for both arms. Two structurally distinct triggers fold onto the
            // same router decision in SplitRowsMethodEmitter: (a) single-cardinality fields
            // whose data-fetcher wants `Record` per key (one row per parent), and (b) the
            // loader.loadMany contract whose per-key value is `Record` regardless of field
            // cardinality. The LOAD_MANY disjunct is unreachable on the Table arm (ctor
            // invariant: Table implies LOAD_ONE), so the unified formula is structurally
            // behavior-identical to the pre-merge split leaf's `!isList`. The
            // {@link LoaderRegistration.Dispatch} projection is the single source of truth for
            // (b) — TypeFetcherGenerator's record-based fetcher reads the same predicate to
            // decide its valueType, so the two emit sites cannot drift.
            return !returnType().wrapper().isList()
                || loaderRegistration().dispatch() == LoaderRegistration.Dispatch.LOAD_MANY;
        }
        @Override public DomainReturnType domainReturnType() {
            return new DomainReturnType.Record(returnType.table());
        }
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
        LookupMapping lookupMapping,
        ParentCorrelation parentCorrelation
    ) implements TableTargetField, LookupField {
        public LookupTableField {
            ParentCorrelation.checkCarrierInvariant(parentCorrelation, joinPath, "LookupTableField");
        }
        @Override public DomainReturnType domainReturnType() {
            return new DomainReturnType.Record(returnType.table());
        }
    }

    /**
 * The {@code @lookupKey} twin of {@link BatchedTableField}: a DataLoader-batched keyed
     * re-query anchor whose SELECT is additionally narrowed by a {@code @lookupKey} VALUES join.
     * One leaf for both parent backings, gated on the stored {@link SourceShape}; the divergence
     * between the pre-merge twins was a strict subset of the non-lookup pair's (same mint/fetcher
     * fork, no Connection arm on either side). See {@link BatchedTableField} for the
     * source-gate and total-lift rationale.
     */
    record BatchedLookupTableField(
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
        LookupMapping lookupMapping,
        ParentCorrelation parentCorrelation
    ) implements TableTargetField, BatchKeyField, LookupField {
        public BatchedLookupTableField {
            // Invariants mirror BatchedTableField's 1/2/3/6; the Connection invariant (4) is
            // deliberately absent — a Connection-shaped lookup is an author-reachable schema
            // (rejected by the validator's "lookup fields must not return a connection" check
            // on both arms), not an unrepresentable generator state.
            java.util.Objects.requireNonNull(lift, "lift");
            KeyLift.checkResidueAgreement(lift, sourceKey, "BatchedLookupTableField");
            ParentCorrelation.checkCarrierInvariant(parentCorrelation, joinPath, "BatchedLookupTableField");
            java.util.Objects.requireNonNull(sourceShape, "sourceShape");
            if (sourceShape == SourceShape.Table) {
                if (!(lift instanceof KeyLift.FkColumns)) {
                    throw new IllegalArgumentException(
                        "BatchedLookupTableField with sourceShape=Table must lift by column projection "
                        + "(KeyLift.FkColumns); a member-read lift (" + lift.getClass().getSimpleName()
                        + ") is a class-backed-parent mechanism");
                }
                if (loaderRegistration.dispatch() != LoaderRegistration.Dispatch.LOAD_ONE) {
                    throw new IllegalArgumentException(
                        "BatchedLookupTableField with sourceShape=Table must dispatch LOAD_ONE; the "
                        + "loadMany contract is an accessor-arity (record-parent) shape");
                }
            }
        }
        @Override
        public boolean emitsSingleRecordPerKey() {
            // One definition for both arms, same formula as BatchedTableField. The classifier
            // rejects @splitQuery + @lookupKey at single cardinality, and the Table arm pins
            // LOAD_ONE, so the Table arm always answers false — identical to the pre-merge
            // split-lookup leaf's inherited default.
            return !returnType().wrapper().isList()
                || loaderRegistration().dispatch() == LoaderRegistration.Dispatch.LOAD_MANY;
        }
        @Override public DomainReturnType domainReturnType() {
            return new DomainReturnType.Record(returnType.table());
        }
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
 * shape as a schema error.
     */
    record TableMethodField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef.TableBoundReturnType returnType,
        List<JoinStep> joinPath,
        MethodRef method,
        Optional<ErrorChannel> errorChannel
    ) implements ChildField, MethodBackedField, WithErrorChannel {
        @Override public DomainReturnType domainReturnType() {
            return new DomainReturnType.Record(returnType.table());
        }
    }

    /**
     * DTO-parent sibling of {@link TableMethodField}: a child field on a class-backed (non-table)
     * parent that uses {@code @tableMethod} to bind a developer-authored static jOOQ table method.
     * The parent has no parent-table alias to join from, so the developer's table is joined against
     * a DataLoader-keyed batch lifted out of the parent DTO via {@link #sourceKey}, mirroring the
     * existing record-sourced {@link BatchedTableField} / {@link BatchedLookupTableField} pattern. {@link #joinPath}
     * is the resolved parent-to-target chain: {@code [fkJoin]} for the FK-auto-derive arm on a
     * jOOQ-table-record-backed parent, the lifter's resolved chain on the {@code @sourceRow} arm.
     *
     * <p>The directive resolver narrows the returnType to
     * {@link ReturnTypeRef.TableBoundReturnType} for both this variant and {@link TableMethodField}.
     */
    record RecordTableMethodField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef.TableBoundReturnType returnType,
        List<JoinStep> joinPath,
        MethodRef method,
        SourceKey sourceKey,
        KeyLift lift,
        LoaderRegistration loaderRegistration,
        Optional<ErrorChannel> errorChannel,
        ParentCorrelation parentCorrelation
    ) implements ChildField, MethodBackedField, BatchKeyField, WithErrorChannel {
        public RecordTableMethodField {
            java.util.Objects.requireNonNull(lift, "lift");
            ParentCorrelation.checkCarrierInvariant(parentCorrelation, joinPath, "RecordTableMethodField");
            KeyLift.checkResidueAgreement(lift, sourceKey, "RecordTableMethodField");
        }
        @Override
        public boolean emitsSingleRecordPerKey() {
            return !returnType().wrapper().isList()
                || loaderRegistration().dispatch() == LoaderRegistration.Dispatch.LOAD_MANY;
        }
        @Override public DomainReturnType domainReturnType() {
            return new DomainReturnType.Record(returnType.table());
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
    ) implements TableTargetField {
        @Override public DomainReturnType domainReturnType() {
            return new DomainReturnType.Record(returnType.table());
        }
    }

    /**
     * A child field on a {@link GraphitronType.TableBackedType} parent returning a multi-table
     * {@link GraphitronType.InterfaceType}. Carries the resolved participants list plus the
     * per-participant {@code joinPath} (one auto-discovered FK chain from the parent table to
     * each participant's table) so the multi-table polymorphic emitter can emit a per-branch
     * WHERE in the stage-1 narrow UNION ALL.
     *
     * <p>{@code participantJoinPaths} is keyed by participant typename — exactly one entry per
     * {@link ParticipantRef.TableBound} participant. {@link ParticipantRef.Unbound} participants
     * are absent from the map; they contribute no SQL branch. Each value is a
     * {@link ParticipantCorrelation} carrying the resolved parent→participant correlation: the
     * classifier decided the shape is supported once, and the emitter cannot represent an
     * unsupported one. Currently every value is a
     * {@link ParticipantCorrelation.KeyTupleWhere} (single-hop FK, auto-discovered or
     * {@code @referenceFor}-disambiguated); the {@link ParticipantCorrelation.JoinedCorrelation}
     * arm is not yet minted.
     *
     * <p>{@code parentSourceKey} and {@code parentResultType} are the parent-object key-extraction
     * strategy and shape, threaded into {@code GeneratorUtils.buildRecordParentKeyExtraction}.
     * Today the classifier produces only catalog-FK / {@code ColumnRead}-reader parent
     * source-keys (table-backed parents); wiring the class-backed-parent classifier arm
     * to reach the lifter and accessor reader permits is follow-up work.
     *
     * <p>{@code parentKeyOwnerTable} is the parent/hub table owning
     * {@code parentSourceKey.columns()} — the parent's {@code @table} on the table-backed arm,
     * the accessor-discovered hub on the record-backed arm — resolved at the same classification
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
        SourceKey parentSourceKey,
        KeyLift parentKeyLift,
        TableRef parentKeyOwnerTable,
        GraphitronType.ResultType parentResultType
    ) implements ChildField {
        public InterfaceField {
            participants = List.copyOf(participants);
            participantJoinPaths = java.util.Map.copyOf(participantJoinPaths);
            // Validator and emitter both read parentSourceKey / parentResultType
            // unconditionally; carry the non-null contract in the type system rather than
            // by reviewer-tracked correspondence.
            java.util.Objects.requireNonNull(parentSourceKey, "parentSourceKey");
            java.util.Objects.requireNonNull(parentKeyLift, "parentKeyLift");
            java.util.Objects.requireNonNull(parentKeyOwnerTable, "parentKeyOwnerTable");
            java.util.Objects.requireNonNull(parentResultType, "parentResultType");
            KeyLift.checkResidueAgreement(parentKeyLift, parentSourceKey, "InterfaceField");
        }
        @Override public DomainReturnType domainReturnType() {
            return new DomainReturnType.Plain(OBJECT_CLASS);
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
        java.util.Map<String, ParticipantCorrelation> participantJoinPaths,
        SourceKey parentSourceKey,
        KeyLift parentKeyLift,
        TableRef parentKeyOwnerTable,
        GraphitronType.ResultType parentResultType
    ) implements ChildField {
        public UnionField {
            participants = List.copyOf(participants);
            participantJoinPaths = java.util.Map.copyOf(participantJoinPaths);
            java.util.Objects.requireNonNull(parentSourceKey, "parentSourceKey");
            java.util.Objects.requireNonNull(parentKeyLift, "parentKeyLift");
            java.util.Objects.requireNonNull(parentKeyOwnerTable, "parentKeyOwnerTable");
            java.util.Objects.requireNonNull(parentResultType, "parentResultType");
            KeyLift.checkResidueAgreement(parentKeyLift, parentSourceKey, "UnionField");
        }
        @Override public DomainReturnType domainReturnType() {
            return new DomainReturnType.Plain(OBJECT_CLASS);
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
         * multiple {@code @table} parents — see {@code GraphitronSchemaBuilderTest.
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
         * {@code null} (a non-built-in scalar leaf: an enum, or an unregistered custom scalar)
         * the per-key {@code V} is peeled off the reflected outer return type on
         * {@link MethodRef#returnType()} via {@link RowsMethodShape#perKeyFromOuter}, so the
         * rows method's declared type is the flat {@code Map<K, V>} matching the service contract
 * rather than a doubly-nested {@code Map<K, Map<K, V>>}. Falls back to the whole
         * reflected type only when the shape isn't peelable, which
         * {@code ServiceDirectiveResolver.validateChildServiceReturnType} rejects at classify time.
         */
        public no.sikt.graphitron.javapoet.TypeName elementType() {
            no.sikt.graphitron.javapoet.TypeName strict = RowsMethodShape.strictPerKeyType(returnType());
            if (strict != null) return strict;
            // Non-built-in scalar leaf (an enum, or an unregistered custom scalar): strictPerKeyType
            // can't name V from the schema, but the service method already declares the outer
            // Map<K, V> / List<V>, so peel V off it. Handing back the whole map instead let
            // outerRowsReturnType wrap it once more, emitting Map<K, Map<K, V>> that doesn't compile
            //. Falls back to the reflected outer type only when the shape isn't peelable, which
            // the resolver's validateChildServiceReturnType now rejects at classify time. Other
            // null-perKey returns (a backing-less ResultReturnType) keep the legacy whole-type fallback.
            if (returnType() instanceof ReturnTypeRef.ScalarReturnType) {
                // loaderRegistration().container() is the field-level projection of the service
                // method's Sourced param container (FieldBuilder.buildServiceLoaderRegistration
                // stores sourced.container() verbatim), the same axis
                // ServiceDirectiveResolver.validateChildServiceReturnType peels with, so emitter
                // and validator can't disagree on isMapped.
                boolean isMapped = loaderRegistration() != null
                    && loaderRegistration().container() == LoaderRegistration.Container.MAPPED_SET;
                var peeled = RowsMethodShape.perKeyFromOuter(method().returnType(), returnType(), isMapped);
                if (peeled != null) return peeled;
            }
            return method().returnType();
        }
        @Override public DomainReturnType domainReturnType() {
            return new DomainReturnType.Plain(OutputField.peelToClassName(method.returnType()));
        }
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
     *     with non-null {@code fqClassName} (i.e. a class-backed parent: Java record or POJO);
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
    ) implements ChildField {
        @Override public DomainReturnType domainReturnType() {
            return new DomainReturnType.Plain(OBJECT_CLASS);
        }
    }

    /**
 * The single data field on an {@code @service} record-composite carrier payload. The
     * {@code @service} method returns a consumer-authored composite (a POJO bundling several jOOQ
     * records, e.g. one {@code FilmRecord} plus a {@code List<ActorRecord>}) as
     * {@code List<Composite>} (list arrival) or one {@code Composite} (single arrival), optionally
     * wrapped in the typed {@code Outcome} when the payload carries an errors field. This data field
     * is a <em>source passthrough projection</em>: its fetcher reads the producer's in-memory
     * record(s) straight off {@code env.getSource()} (narrowing {@code Outcome.Success.value()} under
     * {@link SourceEnvelope#OUTCOME_SUCCESS}) and returns them verbatim, with no
     * DataLoader, no re-fetch, and no SQL. graphql-java then maps each composite element onto the
     * data field's element SDL type ({@code returnType}), whose {@code @field}-mapped {@code @table}
     * children resolve through the record-backed accessor path off the composite.
     *
     * <p>Sibling of the record-sourced {@link BatchedTableField} arm (the {@code @table}-element carrier data field, a
     * PK-keyed re-fetch) and {@link RecordField} (a scalar / accessor read). It differs on the axes
     * the model already separates: its {@link #target()} is {@code listOrSingle(Record)} (the element
     * is a record-backed result type, not a {@code @table} {@code wrap(Table)} and not a scalar
     * {@code Field}), and its {@link #domainReturnType()} is {@code Plain(compositeClass)} (the
     * per-element composite class, there being no single table to name). Carries no
     * {@code SourceKey} / {@code LoaderRegistration} (a passthrough, not a batched load) and no
     * {@code MethodRef} (the producing {@code @service} call is on the parent mutation field, not on
     * this data field, distinguishing it from {@link ServiceRecordField}).
     *
     * <p>{@code returnType} is the data field's element result type ({@link ReturnTypeRef.ResultReturnType}),
     * carrying the arrival cardinality on its {@code wrapper()} and the composite's binary class name
     * on its {@code fqClassName()}. {@code envelope} is the source-envelope fork ({@code DIRECT} for a
     * bare producer return, {@code OUTCOME_SUCCESS} for an errors-bearing carrier whose producer flips
     * to the typed {@code Outcome} wrapper) — carried on the leaf rather than recomputed at emit
     * because the passthrough fetcher and the {@code OutcomeChildArmSwitch} validator both read it. The
     * sibling errors field rides {@link Transport.WrapperArm}, unchanged.
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
        @Override public DomainReturnType domainReturnType() {
            return new DomainReturnType.Plain(ClassName.bestGuess(returnType.fqClassName()));
        }
    }

    /**
     * A child field using {@code @externalField} — the developer provides a static method
     * returning a jOOQ {@code Field<X>} that is inlined into the parent's projection at
     * generation time. The method handles the SQL-side computation; runtime wiring uses
     * a {@code LightFetcher}-wrapped read of the aliased column, keyed on the GraphQL field name.
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
    ) implements ChildField, MethodBackedField {
        @Override public DomainReturnType domainReturnType() {
            return new DomainReturnType.Plain(OutputField.peelToClassName(method.returnType()));
        }
    }

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
    ) implements ChildField {
        @Override public DomainReturnType domainReturnType() {
            return new DomainReturnType.Plain(
                column != null ? column.columnType() : OBJECT_CLASS);
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
        @Override public DomainReturnType domainReturnType() {
            return new DomainReturnType.Plain(OBJECT_CLASS);
        }
    }

    /**
     * Where the errors-field data fetcher reads its value from at request time. Sealed so
     * {@code FetcherEmitter.dataFetcherValue}'s {@code ErrorsField} arm dispatches with
     * compiler-enforced exhaustiveness; a future third arm forces every consumer site to
     * acknowledge it.
     *
     * <ul>
     *   <li>{@link PayloadAccessor} : the parent payload exposes the errors list as a
     *       property reachable via graphql-java's default {@code PropertyDataFetcher}
     *       (record accessor, JavaBean getter, or public field). Today's only arm.</li>
     *   <li>{@link LocalContext} : the fetcher reads from
     *       {@code env.getLocalContext()}. Pairs with an
     *       {@link ErrorChannel.LocalContext} catch arm that ships
     *       {@code DataFetcherResult.<R>newResult().data(null).localContext(errors).build()};
     *       the parent payload is bypassed entirely.</li>
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
         * {@code env.getSource()} of an in-scope ({@code @service} / {@code @tableMethod}) outcome
         * type. The errors-field fetcher reads {@code ErrorList.errors} off the non-null
         * {@code Outcome} source; sibling data fields project {@code Success.value} (rendering null
         * on the error arm). Pairs with an {@link ErrorChannel.Mapped} catch arm. Every immediate
         * child of such an outcome type must arm-switch (pinned by
         * {@code GraphitronSchemaValidator.validateOutcomeChildArmSwitch}).
         */
        record WrapperArm() implements Transport {}
    }
}
