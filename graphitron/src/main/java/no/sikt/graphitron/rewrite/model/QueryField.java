package no.sikt.graphitron.rewrite.model;

import graphql.language.SourceLocation;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import no.sikt.graphitron.model.jooq.ColumnRef;

/**
 * A field on the {@code Query} type. Read-only. All create a new scope or enter service scope.
 *
 * <p>The two primary SQL-generating query field types ({@link QueryTableField},
 * {@link QueryTableInterfaceField}) carry the same SQL-generation components as their
 * child-field counterparts:
 * <ul>
 *   <li>{@code filters} — WHERE-clause contributions; may be empty.</li>
 *   <li>{@code orderBy} — authoritative ordering; always non-null.</li>
 *   <li>{@code pagination} — Relay pagination args; {@code null} when absent.</li>
 *   <li>{@code lookup}: the resolved {@code @lookupKey} correspondence on
 *       {@link QueryTableField}; {@link LookupResolution.None} when absent.</li>
 * </ul>
 */
public sealed interface QueryField extends RootField
    permits QueryField.QueryTableField,
            QueryField.QueryNodeField, QueryField.QueryNodesField,
            QueryField.QueryTableInterfaceField, QueryField.QueryInterfaceField,
            QueryField.QueryUnionField {

    /**
     * Every {@code QueryField} leaf is on the {@code Query} root, so the source is
     * {@link Source.Root.Query}; the root is the empty product and ignores {@code parentArrival}.
     */
    @Override default Source source(Arrival parentArrival) { return new Source.Root.Query(); }

    @Override default Target target() {
        return switch (this) {
            // Catalog table reads: wrap(...) keeps the Connection -> Single(Connection) decomposition.
            case QueryTableField f -> OutputField.wrap(f.returnType().wrapper(), new TargetShape.Table());
            case QueryTableInterfaceField f -> OutputField.wrap(f.returnType().wrapper(), new TargetShape.Table());
            // Polymorphic roots are catalog-bound (every participant is a @table/NodeType): the shape is
            // Interface / Union; mapping() derives Table.
            case QueryInterfaceField f -> OutputField.wrap(f.returnType().wrapper(), new TargetShape.Interface());
            case QueryUnionField f -> OutputField.wrap(f.returnType().wrapper(), new TargetShape.Union());
            case QueryNodeField f -> OutputField.wrap(f.returnType().wrapper(), new TargetShape.Interface());
            case QueryNodesField f -> OutputField.wrap(f.returnType().wrapper(), new TargetShape.Interface());
        };
    }

    /**
     * A root table read whose FROM starts at the return type's own table or a routine chain.
     * The source axis is the sealed {@link RoutineResolution}: the {@code Chain} arm carries a
     * jOOQ database routine ({@code @routine}) whose table chain terminates on the field's
     * {@code @table} return type. Source and read surface are independent: a chain-sourced read
     * filters, sorts and paginates through the same components an anchor-sourced one does,
     * resolved against the chain's terminus.
     */
    record QueryTableField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef.TableBoundReturnType returnType,
        List<WhereFilter> filters,
        OrderBySpec orderBy,
        PaginationSpec pagination,
        LookupResolution lookup,
        RoutineResolution routine
    ) implements QueryField, SqlGeneratingField {
        public QueryTableField {
            java.util.Objects.requireNonNull(lookup, "lookup");
            java.util.Objects.requireNonNull(routine, "routine");
            if (routine instanceof RoutineResolution.Chain c) {
                // The one read-surface axis a chain still cannot carry, stated against the axis
                // that owns it rather than folded into a conjunction: keyed lookup over a
                // routine chain is a classify-time typed deferral (the key tuple a lookup joins
                // on is the terminus primary key, which a routine result has none of), so a
                // resolved lookup beside a chain is a classifier bug, not an author error.
                if (!(lookup instanceof LookupResolution.None)) {
                    throw new IllegalArgumentException(
                        "QueryTableField with a routine chain must carry LookupResolution.None; "
                        + "the classifier defers @lookupKey on a routine-backed field before "
                        + "construction");
                }
                // Terminus invariant: the projected @table type is the chain's last node.
                if (!c.chain().terminus().denotesSameTableAs(returnType.table())) {
                    throw new IllegalArgumentException(
                        "QueryTableField routine terminus mismatch: the chain ends on '"
                        + c.chain().terminus().tableName() + "' but the field's @table type is "
                        + "bound to '" + returnType.table().tableName() + "'; the classifier's "
                        + "terminus rule must reject this before construction");
                }
            }
        }
        @Override public DomainReturnType domainReturnType() {
            return new DomainReturnType.Record(returnType.table());
        }
    }

    record QueryNodeField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef.PolymorphicReturnType returnType
    ) implements QueryField {
        @Override public DomainReturnType domainReturnType() {
            return new DomainReturnType.NoClaim();
        }
    }

    record QueryNodesField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef.PolymorphicReturnType returnType
    ) implements QueryField {
        @Override public DomainReturnType domainReturnType() {
            return new DomainReturnType.NoClaim();
        }
    }

    record QueryTableInterfaceField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef.TableBoundReturnType returnType,
        ColumnRef discriminatorColumn,
        List<String> knownDiscriminatorValues,
        List<ParticipantRef> participants,
        List<WhereFilter> filters,
        OrderBySpec orderBy,
        PaginationSpec pagination
    ) implements QueryField, SqlGeneratingField {
        @Override public DomainReturnType domainReturnType() {
            return new DomainReturnType.Record(returnType.table());
        }
    }

    /**
     * A root query field returning a multi-table {@link GraphitronType.InterfaceType}.
     * Carries the resolved participants list so the multi-table polymorphic fetcher emitter can
     * drive its two-stage SQL: a narrow UNION ALL projecting {@code (__typename, __pk0__, ...)}
     * per branch and a per-typename batched lookup using {@code <Type>.$project}.
     */
    record QueryInterfaceField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef.PolymorphicReturnType returnType,
        List<ParticipantRef> participants,
        List<ParticipantFilters> participantFilters,
        List<NodeIdArgDispatch> nodeIdArgDispatches,
        Optional<PolymorphicOrdering> ordering
    ) implements QueryField, ParticipantFilterField, PolymorphicOrderingField {
        public QueryInterfaceField {
            participants = List.copyOf(participants);
            participantFilters = List.copyOf(participantFilters);
            nodeIdArgDispatches = List.copyOf(nodeIdArgDispatches);
            Objects.requireNonNull(ordering, "ordering");
        }
        @Override public DomainReturnType domainReturnType() {
            return new DomainReturnType.NoClaim();
        }
    }

    /**
     * A root query field returning a multi-table {@link GraphitronType.UnionType}.
     * Same two-stage shape as {@link QueryInterfaceField}; differs only in the source of the
     * participant set (union member types vs. interface implementers).
     */
    record QueryUnionField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef.PolymorphicReturnType returnType,
        List<ParticipantRef> participants,
        List<ParticipantFilters> participantFilters,
        List<NodeIdArgDispatch> nodeIdArgDispatches,
        Optional<PolymorphicOrdering> ordering
    ) implements QueryField, ParticipantFilterField, PolymorphicOrderingField {
        public QueryUnionField {
            participants = List.copyOf(participants);
            participantFilters = List.copyOf(participantFilters);
            nodeIdArgDispatches = List.copyOf(nodeIdArgDispatches);
            Objects.requireNonNull(ordering, "ordering");
        }
        @Override public DomainReturnType domainReturnType() {
            return new DomainReturnType.NoClaim();
        }
    }
}
