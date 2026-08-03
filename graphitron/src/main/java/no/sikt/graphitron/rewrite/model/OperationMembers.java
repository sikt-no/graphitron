package no.sikt.graphitron.rewrite.model;

import no.sikt.graphitron.rewrite.model.OperationMember.Condition;
import no.sikt.graphitron.rewrite.model.OperationMember.Join;
import no.sikt.graphitron.rewrite.model.OperationMember.Kind;
import no.sikt.graphitron.rewrite.model.OperationMember.Lookup;
import no.sikt.graphitron.rewrite.model.OperationMember.NodeResolve;
import no.sikt.graphitron.rewrite.model.OperationMember.OrderBy;
import no.sikt.graphitron.rewrite.model.OperationMember.Paginate;
import no.sikt.graphitron.rewrite.model.OperationMember.Reentry;
import no.sikt.graphitron.rewrite.model.OperationMember.Select;
import no.sikt.graphitron.rewrite.model.OperationMember.ServiceCall;
import no.sikt.graphitron.rewrite.model.OperationMember.Write;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The leaf-to-member crosswalk: derives a coordinate's {@link OperationMember} set from its
 * classified leaf by one compile-total switch, the vocabulary mapping between the summary
 * {@link Operation} column and the member relation written as code. Since the keystone the
 * schema view reads the minted trigger-fact production instead of this switch; the projection
 * survives the coexistence window as the membership-agreement pin's comparison side, as the
 * fallback for schemas built without the classify walk, and as the leaf-local derivation
 * behind {@link OutputField#requiresReFetch()} / {@link OutputField#emitsKeyedReQuery()}, and
 * a new leaf still fails compilation here until the crosswalk covers it.
 *
 * <p>{@link #DECLARED_SHAPES} is the per-leaf co-occurrence declaration: the required member
 * kinds plus the payload-gated optional ones. The admitted combination set is the <em>image</em>
 * of this declaration (per leaf, the required kinds unioned with any subset of the optional
 * kinds), mechanically enumerable, and {@link #membersOf} validates every produced set against
 * it at construction, so the fence cannot drift from the projection that defines it. A member
 * combination outside the image is rejected; a combination inside the image that no leaf
 * produces yet is the additive model working as intended, visible as admissible-versus-observed
 * data rather than a surprise.
 *
 * <p>Three crosswalk decisions bound here rather than left per-arm:
 * <ul>
 *   <li><b>The condition member is keyed {@code (coordinate, table)}</b>, mirroring the
 *       back-half condition relation's key so the join between the two relations is 1:1; a
 *       polymorphic root mints one condition member per table-bound participant carrying
 *       filters.</li>
 *   <li><b>Record-read and nesting coordinates map to the empty set</b> even though the corpus
 *       summarises them {@code Fetch} / {@code Nest}: a Java-side target
 *       ({@link TargetShape.Record} / {@link TargetShape.Field}, or a catalog column read off an
 *       in-memory producer record) triggers no query-composing operation, and the DataFetcher's
 *       existence is the fact.</li>
 *   <li><b>The reentry member is minted centrally</b> ({@code mintsReentry}), from the same
 *       facts the site-level predicate always read (a bare catalog table target, a received or
 *       produced record, minus the root {@code @service} passthrough), so
 *       {@link OutputField#emitsKeyedReQuery()} reads member presence instead of recomputing a
 *       compound predicate per site.</li>
 * </ul>
 */
public final class OperationMembers {

    private OperationMembers() {}

    /**
     * One leaf's declared member shape: the kinds every instance of the leaf must produce and
     * the payload-gated kinds it may produce. The two sets are disjoint; everything outside
     * their union is outside the leaf's admitted image.
     */
    public record DeclaredShape(Set<Kind> required, Set<Kind> optional) {
        public DeclaredShape {
            required = Set.copyOf(required);
            optional = Set.copyOf(optional);
            for (Kind k : optional) {
                if (required.contains(k)) {
                    throw new IllegalArgumentException(
                        "a member kind is required or optional, never both: " + k);
                }
            }
        }
    }

    private static DeclaredShape shape(Set<Kind> required, Set<Kind> optional) {
        return new DeclaredShape(required, optional);
    }

    /** The table-read optionals every filter-surface-bearing table leaf shares. */
    private static final Set<Kind> TABLE_READ_OPTIONALS =
        Set.of(Kind.CONDITION, Kind.ORDER_BY, Kind.PAGINATE);

    /** {@link #TABLE_READ_OPTIONALS} plus the child-side reference fact. */
    private static final Set<Kind> CHILD_TABLE_READ_OPTIONALS =
        Set.of(Kind.JOIN, Kind.CONDITION, Kind.ORDER_BY, Kind.PAGINATE);

    /** {@link #CHILD_TABLE_READ_OPTIONALS} plus the record-sourced keyed re-query. */
    private static final Set<Kind> BATCHED_TABLE_READ_OPTIONALS =
        Set.of(Kind.JOIN, Kind.CONDITION, Kind.ORDER_BY, Kind.PAGINATE, Kind.REENTRY);

    /**
     * The given optionals plus the lookup member: the three fetch leaves that carry a
     * {@link LookupResolution} gate it as an optional kind, exactly as the carried window
     * gates paginate. The polymorphic and service leaves sharing the base sets resolve no
     * lookup, so their declared shapes stay narrower.
     */
    private static Set<Kind> withLookupOptional(Set<Kind> base) {
        var out = java.util.EnumSet.copyOf(base);
        out.add(Kind.LOOKUP);
        return Set.copyOf(out);
    }

    /**
     * The per-leaf co-occurrence declaration (see the class javadoc). One entry per sealed
     * {@link OutputField} leaf; totality against the sealed hierarchy is pinned by the
     * projection's coverage test, and {@link #membersOf}'s switch is compile-total
     * independently, so neither enumeration can silently lag the other.
     */
    public static final Map<Class<? extends OutputField>, DeclaredShape> DECLARED_SHAPES = Map.ofEntries(
        // Query roots.
        Map.entry(QueryField.QueryTableField.class,
            shape(Set.of(Kind.SELECT), withLookupOptional(TABLE_READ_OPTIONALS))),
        Map.entry(QueryField.QueryRoutineTableField.class,
            shape(Set.of(Kind.SELECT), Set.of())),
        Map.entry(QueryField.QueryTableInterfaceField.class,
            shape(Set.of(Kind.SELECT), TABLE_READ_OPTIONALS)),
        Map.entry(QueryField.QueryInterfaceField.class,
            shape(Set.of(Kind.SELECT), Set.of(Kind.CONDITION))),
        Map.entry(QueryField.QueryUnionField.class,
            shape(Set.of(Kind.SELECT), Set.of(Kind.CONDITION))),
        Map.entry(QueryField.QueryNodeField.class,
            shape(Set.of(Kind.NODE_RESOLVE), Set.of())),
        Map.entry(QueryField.QueryNodesField.class,
            shape(Set.of(Kind.NODE_RESOLVE), Set.of())),
        Map.entry(QueryField.QueryServiceTableField.class,
            shape(Set.of(Kind.SERVICE_CALL), Set.of())),
        Map.entry(QueryField.QueryServiceRecordField.class,
            shape(Set.of(Kind.SERVICE_CALL), Set.of())),
        Map.entry(QueryField.QueryServicePolymorphicField.class,
            shape(Set.of(Kind.SERVICE_CALL), Set.of())),
        Map.entry(QueryField.QueryServiceTableInterfaceField.class,
            shape(Set.of(Kind.SERVICE_CALL), Set.of())),

        // Mutation roots. The projected / discriminated direct-DML returns re-query at their
        // own site, so the direct-return leaf declares REENTRY optional. Read alone that
        // widens DELETE (whose direct return is always encoded and mints no reentry): the
        // real fence moved below the image check, to the leaf constructor, which rejects a
        // Delete arm beside any table-bound return, so a reentry-minting DELETE is
        // unconstructible rather than image-rejected.
        Map.entry(MutationField.DmlTableField.class,
            shape(Set.of(Kind.WRITE), Set.of(Kind.REENTRY))),
        Map.entry(MutationField.MutationRoutineWriteField.class,
            shape(Set.of(Kind.WRITE), Set.of())),
        Map.entry(MutationField.MutationServiceTableField.class,
            shape(Set.of(Kind.SERVICE_CALL), Set.of())),
        Map.entry(MutationField.MutationServiceRecordField.class,
            shape(Set.of(Kind.SERVICE_CALL), Set.of())),
        Map.entry(MutationField.MutationServicePolymorphicField.class,
            shape(Set.of(Kind.SERVICE_CALL), Set.of())),
        Map.entry(MutationField.MutationServiceTableInterfaceField.class,
            shape(Set.of(Kind.SERVICE_CALL), Set.of())),
        Map.entry(MutationField.MutationDmlRecordField.class,
            shape(Set.of(Kind.WRITE), Set.of())),
        Map.entry(MutationField.MutationBulkDmlRecordField.class,
            shape(Set.of(Kind.WRITE), Set.of())),

        // Child fields.
        Map.entry(ChildField.ColumnBackedField.class,
            shape(Set.of(Kind.SELECT), Set.of())),
        Map.entry(ChildField.ColumnBackedReferenceField.class,
            shape(Set.of(Kind.SELECT), Set.of(Kind.JOIN))),
        Map.entry(ChildField.ParticipantColumnReferenceField.class,
            shape(Set.of(Kind.SELECT, Kind.JOIN), Set.of())),
        Map.entry(ChildField.TableField.class,
            shape(Set.of(Kind.SELECT), withLookupOptional(CHILD_TABLE_READ_OPTIONALS))),
        Map.entry(ChildField.BatchedTableField.class,
            shape(Set.of(Kind.SELECT), withLookupOptional(BATCHED_TABLE_READ_OPTIONALS))),
        Map.entry(ChildField.TableInterfaceField.class,
            shape(Set.of(Kind.SELECT), CHILD_TABLE_READ_OPTIONALS)),
        Map.entry(ChildField.InterfaceField.class,
            shape(Set.of(Kind.SELECT), Set.of())),
        Map.entry(ChildField.UnionField.class,
            shape(Set.of(Kind.SELECT), Set.of())),
        Map.entry(ChildField.BatchedInterfaceField.class,
            shape(Set.of(Kind.SELECT), Set.of())),
        Map.entry(ChildField.BatchedUnionField.class,
            shape(Set.of(Kind.SELECT), Set.of())),
        Map.entry(ChildField.NestingField.class,
            shape(Set.of(), Set.of())),
        Map.entry(ChildField.PivotField.class,
            shape(Set.of(Kind.PIVOT, Kind.JOIN), Set.of())),
        Map.entry(ChildField.BatchedPivotField.class,
            shape(Set.of(Kind.PIVOT, Kind.JOIN), Set.of())),
        Map.entry(ChildField.PivotSlotField.class,
            shape(Set.of(), Set.of())),
        Map.entry(ChildField.ServiceTableField.class,
            shape(Set.of(Kind.SERVICE_CALL), BATCHED_TABLE_READ_OPTIONALS)),
        Map.entry(ChildField.ServiceRecordField.class,
            shape(Set.of(Kind.SERVICE_CALL), Set.of())),
        Map.entry(ChildField.RecordReadField.class,
            shape(Set.of(), Set.of())),
        Map.entry(ChildField.RecordCompositeField.class,
            shape(Set.of(), Set.of())),
        Map.entry(ChildField.ComputedField.class,
            shape(Set.of(Kind.SELECT), Set.of(Kind.JOIN))),
        Map.entry(ChildField.SingleRecordIdField.class,
            shape(Set.of(), Set.of())),
        Map.entry(ChildField.SingleRecordIdFieldFromReturning.class,
            shape(Set.of(), Set.of())),
        Map.entry(ChildField.ErrorsField.class,
            shape(Set.of(), Set.of()))
    );

    /** The leaf's declared shape; throws when the declaration map lags the sealed hierarchy. */
    public static DeclaredShape declaredShapeOf(Class<? extends OutputField> leafClass) {
        DeclaredShape declared = DECLARED_SHAPES.get(leafClass);
        if (declared == null) {
            throw new IllegalStateException(
                "no declared member shape for leaf " + leafClass.getSimpleName()
                + "; add its entry to OperationMembers.DECLARED_SHAPES");
        }
        return declared;
    }

    /**
     * The coordinate's operation member set, derived from its classified leaf. Validated
     * against the leaf's {@link #declaredShapeOf declared shape} and the per-kind multiplicity
     * of the {@code (coordinate, member)} key at construction.
     */
    public static List<OperationMember> membersOf(OutputField leaf) {
        List<OperationMember> base = switch (leaf) {
            // --- Query roots ---
            case QueryField.QueryTableField f ->
                withResolvedLookup(tableRead(f.returnType().table(), List.of(), f.filters(), f.orderBy(), f.pagination()),
                    f.lookup());
            case QueryField.QueryRoutineTableField _ -> List.of(new Select());
            case QueryField.QueryTableInterfaceField f ->
                tableRead(f.returnType().table(), List.of(), f.filters(), f.orderBy(), f.pagination());
            case QueryField.QueryInterfaceField f -> polymorphicRootRead(f.participantFilters());
            case QueryField.QueryUnionField f -> polymorphicRootRead(f.participantFilters());
            case QueryField.QueryNodeField _ -> List.of(new NodeResolve());
            case QueryField.QueryNodesField _ -> List.of(new NodeResolve());
            case QueryField.QueryServiceTableField f -> List.of(structuredServiceCall(f.serviceMethodCall()));
            case QueryField.QueryServiceRecordField f -> List.of(structuredServiceCall(f.serviceMethodCall()));
            case QueryField.QueryServicePolymorphicField f -> List.of(structuredServiceCall(f.serviceMethodCall()));
            case QueryField.QueryServiceTableInterfaceField f -> List.of(structuredServiceCall(f.serviceMethodCall()));

            // --- Mutation roots ---
            // The write payload is the leaf's carried component, by identity (DmlWriteField).
            case DmlWriteField f -> List.of(f.write());
            case MutationField.MutationRoutineWriteField _ -> List.of(new Write.RoutineWrite());
            case MutationField.MutationServiceTableField f -> List.of(structuredServiceCall(f.serviceMethodCall()));
            case MutationField.MutationServiceRecordField f -> List.of(structuredServiceCall(f.serviceMethodCall()));
            case MutationField.MutationServicePolymorphicField f -> List.of(structuredServiceCall(f.serviceMethodCall()));
            case MutationField.MutationServiceTableInterfaceField f -> List.of(structuredServiceCall(f.serviceMethodCall()));

            // --- Child fields: catalog column projections ---
            case ChildField.ColumnBackedField _ -> List.of(new Select());
            case ChildField.ColumnBackedReferenceField f -> columnReference(f.joinPath());
            case ChildField.ParticipantColumnReferenceField _ -> List.of(new Select(), new Join());
            case ChildField.ComputedField f -> columnReference(f.joinPath());

            // --- Child fields: table-bound reads ---
            case ChildField.TableField f ->
                withResolvedLookup(tableRead(f.returnType().table(), f.joinPath(), f.filters(), f.orderBy(), f.pagination()),
                    f.lookup());
            case ChildField.BatchedTableField f ->
                withResolvedLookup(tableRead(f.returnType().table(), f.joinPath(), f.filters(), f.orderBy(), f.pagination()),
                    f.lookup());
            case ChildField.TableInterfaceField f ->
                tableRead(f.returnType().table(), f.joinPath(), f.filters(), f.orderBy(), f.pagination());

            // --- Child fields: polymorphic reads (no field-level filter surface on these leaves) ---
            case ChildField.InterfaceField _ -> List.of(new Select());
            case ChildField.UnionField _ -> List.of(new Select());
            case ChildField.BatchedInterfaceField _ -> List.of(new Select());
            case ChildField.BatchedUnionField _ -> List.of(new Select());

            // --- Child fields: pivot. The member row is the leaf's carried component, by
            // identity (PivotSpecField), the DmlWriteField discipline. ---
            case ChildField.PivotSpecField f -> List.of(f.pivot(), new Join());

            // --- Child fields: service calls (reflected carrier) ---
            case ChildField.ServiceTableField f -> serviceTableRead(f);
            case ChildField.ServiceRecordField f -> List.of(new ServiceCall(
                new ServiceCallCarrier.ReflectedMethod(f.method())));

            // --- Child fields: record reads, regroups and pass-throughs: the empty set.
            // The DataFetcher's existence is the fact; empty is a value. ---
            case ChildField.NestingField _ -> List.of();
            case ChildField.PivotSlotField _ -> List.of();
            case ChildField.RecordReadField _ -> List.of();
            case ChildField.RecordCompositeField _ -> List.of();
            case ChildField.SingleRecordIdField _ -> List.of();
            case ChildField.SingleRecordIdFieldFromReturning _ -> List.of();
            case ChildField.ErrorsField _ -> List.of();
        };
        return finish(leaf, base);
    }

    /** [Select] plus the payload-gated condition, orderBy and paginate members, plus join off the path. */
    private static List<OperationMember> tableRead(TableRef table, List<JoinStep> joinPath,
            List<WhereFilter> filters, OrderBySpec orderBy, PaginationSpec pagination) {
        var members = new ArrayList<OperationMember>();
        members.add(new Select());
        if (!joinPath.isEmpty()) {
            members.add(new Join());
        }
        if (!filters.isEmpty()) {
            members.add(new Condition.OnReturnTable(table, filters));
        }
        if (!(orderBy instanceof OrderBySpec.None)) {
            members.add(new OrderBy(orderBy));
        }
        if (pagination != null) {
            members.add(new Paginate(pagination));
        }
        return members;
    }

    private static List<OperationMember> withResolvedLookup(List<OperationMember> members,
            LookupResolution lookup) {
        if (lookup instanceof LookupResolution.Keyed keyed) {
            members.add(new Lookup(keyed.mapping()));
        }
        return members;
    }

    /**
     * The leaf's resolved lookup axis: {@link LookupResolution.Keyed} exactly when the
     * coordinate mints a lookup member. Total over the sealed hierarchies; the leaves outside
     * the table-read families resolve no lookup structurally, so the default arm states a
     * structural fact rather than swallowing one.
     */
    public static LookupResolution lookupResolutionOf(OutputField leaf) {
        return switch (leaf) {
            case QueryField.QueryTableField f -> f.lookup();
            case ChildField.TableTargetField ttf -> ttf.lookup();
            default -> LookupResolution.None.INSTANCE;
        };
    }

    /** A catalog column projection with an optional reference traversal. */
    private static List<OperationMember> columnReference(List<JoinStep> joinPath) {
        var members = new ArrayList<OperationMember>();
        members.add(new Select());
        if (!joinPath.isEmpty()) {
            members.add(new Join());
        }
        return members;
    }

    /**
     * A multi-table polymorphic root: the UNION ALL select plus one condition member per
     * table-bound participant carrying filters, the per-participant filter surface the one-arm
     * summary could not hold.
     */
    private static List<OperationMember> polymorphicRootRead(List<ParticipantFilters> participantFilters) {
        var members = new ArrayList<OperationMember>();
        members.add(new Select());
        for (var pf : participantFilters) {
            if (!pf.filters().isEmpty()) {
                members.add(new Condition.OnParticipant(pf.participant(), pf.filters()));
            }
        }
        return members;
    }

    /**
     * The child {@code @service} table read: the service call plus whatever of the leaf's
     * table-read surface is populated (its filter, ordering, pagination and join slots exist
     * and were silently outside the one-arm summary).
     */
    private static List<OperationMember> serviceTableRead(ChildField.ServiceTableField f) {
        var members = new ArrayList<OperationMember>();
        members.add(new ServiceCall(new ServiceCallCarrier.ReflectedMethod(f.method())));
        if (!f.joinPath().isEmpty()) {
            members.add(new Join());
        }
        if (!f.filters().isEmpty()) {
            members.add(new Condition.OnReturnTable(f.returnType().table(), f.filters()));
        }
        if (!(f.orderBy() instanceof OrderBySpec.None)) {
            members.add(new OrderBy(f.orderBy()));
        }
        if (f.pagination() != null) {
            members.add(new Paginate(f.pagination()));
        }
        return members;
    }

    private static ServiceCall structuredServiceCall(ServiceMethodCall call) {
        return new ServiceCall(new ServiceCallCarrier.StructuredCall(call));
    }

    /**
     * The site-level reentry mint: a bare catalog {@link TargetShape.Table} target whose value
     * comes from a received record (a record-sourced child) or a record-producing member (a
     * service call or DML write), minus the root {@code @service} passthrough whose
     * re-projection is realized by the downstream child fetchers.
     */
    private static boolean mintsReentry(OutputField leaf, List<OperationMember> base) {
        if (!(leaf.target().shape() instanceof TargetShape.Table)) {
            return false;
        }
        boolean receivedRecord = leaf instanceof ChildField cf && cf.sourceShape() == SourceShape.Record;
        boolean producedRecord = base.stream().anyMatch(OperationMember::producesRecord);
        if (!receivedRecord && !producedRecord) {
            return false;
        }
        boolean rootServicePassthrough = !(leaf instanceof ChildField)
            && base.stream().anyMatch(m -> m instanceof ServiceCall);
        return !rootServicePassthrough;
    }

    /**
     * Appends the centrally minted reentry member and validates the finished set through
     * {@link #validateAgainstDeclaredShape}.
     */
    private static List<OperationMember> finish(OutputField leaf, List<OperationMember> base) {
        var members = new ArrayList<>(base);
        if (mintsReentry(leaf, members)) {
            members.add(new Reentry());
        }
        return validateAgainstDeclaredShape(leaf, members);
    }

    /**
     * Validates a finished member set against the leaf's declared shape: the declared required
     * kinds are all present, every produced kind sits inside the declared image, and the
     * {@code (coordinate, member)} key holds (one member per kind, conditions distinct per
     * table). Shared by this projection and the trigger-fact production, so the co-occurrence
     * fence has one statement regardless of which production ran.
     */
    public static List<OperationMember> validateAgainstDeclaredShape(OutputField leaf, List<OperationMember> members) {
        DeclaredShape declared = declaredShapeOf(leaf.getClass());
        for (Kind required : declared.required()) {
            if (members.stream().noneMatch(m -> m.kind() == required)) {
                throw new IllegalStateException(
                    "leaf " + leaf.getClass().getSimpleName() + " at " + leaf.parentTypeName()
                    + "." + leaf.name() + " declares member kind " + required
                    + " required but produced none");
            }
        }
        var seenKeys = new HashSet<String>();
        for (OperationMember m : members) {
            Kind kind = m.kind();
            if (!declared.required().contains(kind) && !declared.optional().contains(kind)) {
                throw new IllegalStateException(
                    "leaf " + leaf.getClass().getSimpleName() + " at " + leaf.parentTypeName()
                    + "." + leaf.name() + " produced member kind " + kind
                    + " outside its declared shape " + declared);
            }
            String key = m instanceof Condition c ? kind + "@" + c.table().tableName() : kind.name();
            if (!seenKeys.add(key)) {
                throw new IllegalStateException(
                    "leaf " + leaf.getClass().getSimpleName() + " at " + leaf.parentTypeName()
                    + "." + leaf.name() + " produced a duplicate member key " + key
                    + "; the (coordinate, member) key admits one member per kind, conditions "
                    + "keyed per table");
            }
        }
        return List.copyOf(members);
    }
}
