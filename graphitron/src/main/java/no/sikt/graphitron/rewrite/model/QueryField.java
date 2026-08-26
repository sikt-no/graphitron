package no.sikt.graphitron.rewrite.model;

import graphql.language.SourceLocation;

import java.util.List;
import java.util.Optional;

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
 *
 * <p>Service fields ({@link QueryServiceTableField}, {@link QueryServiceRecordField})
 * do not carry these components — the developer-controlled method replaces SQL generation entirely.
 */
public sealed interface QueryField extends RootField
    permits QueryField.QueryTableField,
            QueryField.QueryNodeField, QueryField.QueryNodesField,
            QueryField.QueryTableInterfaceField, QueryField.QueryInterfaceField,
            QueryField.QueryUnionField,
            QueryField.QueryServiceTableField, QueryField.QueryServiceRecordField,
            QueryField.QueryServicePolymorphicField,
            QueryField.QueryServiceTableInterfaceField {

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
            case QueryServiceTableField f -> OutputField.wrap(f.returnType().wrapper(), new TargetShape.Table());
            case QueryServiceRecordField f -> OutputField.listOrSingle(f.returnType().wrapper(), new TargetShape.Record());
            // Polymorphic roots are catalog-bound (every participant is a @table/NodeType): the shape is
            // Interface / Union; mapping() derives Table.
            case QueryInterfaceField f -> OutputField.wrap(f.returnType().wrapper(), new TargetShape.Interface());
            case QueryUnionField f -> OutputField.wrap(f.returnType().wrapper(), new TargetShape.Union());
            // Service-polymorphic returns are interface-only (union/table-interface rejected at
            // classify time) and route through the __typename-column TypeResolver.
            case QueryServicePolymorphicField f -> OutputField.wrap(f.returnType().wrapper(), new TargetShape.Interface());
            // Single-table service interface return: payload is a raw Record / List<Record>
            // routed by the discriminated TypeResolver, same wiring shape as route (a). Interface
            // (not Table) keeps requiresReFetch() false so the re-fetch mirror agrees with the
            // service fetcher, which does the by-PK re-projection itself.
            case QueryServiceTableInterfaceField f -> OutputField.wrap(f.returnType().wrapper(), new TargetShape.Interface());
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
        List<NodeIdArgDispatch> nodeIdArgDispatches
    ) implements QueryField, ParticipantFilterField {
        public QueryInterfaceField {
            participants = List.copyOf(participants);
            participantFilters = List.copyOf(participantFilters);
            nodeIdArgDispatches = List.copyOf(nodeIdArgDispatches);
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
        List<NodeIdArgDispatch> nodeIdArgDispatches
    ) implements QueryField, ParticipantFilterField {
        public QueryUnionField {
            participants = List.copyOf(participants);
            participantFilters = List.copyOf(participantFilters);
            nodeIdArgDispatches = List.copyOf(nodeIdArgDispatches);
        }
        @Override public DomainReturnType domainReturnType() {
            return new DomainReturnType.NoClaim();
        }
    }

    /**
     * A root query field backed by a developer-provided service method, returning a table-mapped type.
     *
     * <p>Parameter binding (including context arguments) is fully encoded in
     * {@link MethodRef#params()} via {@link ParamSource}.
     *
     * <p>{@code errorChannel} carries the carrier-side typed-error wiring when this field's
     * payload includes an {@code errors} field, which a table-bound return never does
     * (channel resolution requires a class-backed result return), pinned at the build boundary
     * by the validator's root service arm.
     *
     * <p><b>Reentry realization.</b> This leaf is both value-level re-fetch
     * ({@link OutputField#requiresReFetch()}) and site-level reentry
     * ({@link OutputField#emitsKeyedReQuery()}): the records the developer's method returns are
     * key carriers, and the emitted fetcher lifts their primary keys and returns the reentry
     * companion's re-selected rows. So the author's obligation is to populate the key columns;
     * every other selected field comes from the table. The emitted payload is the companion's
     * ({@code List<Record>} / {@code Record}), which is what {@code env.getSource()} hands every
     * child hanging off the parent, a projected row exactly as under a catalog read.
     */
    record QueryServiceTableField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef.TableBoundReturnType returnType,
        ServiceMethodCall serviceMethodCall,
        Optional<ErrorChannel.Mapped> errorChannel
    ) implements QueryField, ServiceField, WithErrorChannel {
        /**
         * See {@link ChildField.ServiceTableField#domainReturnType()}: the typed
         * {@code XRecord} is consumer-equivalent to a {@code Record(table)} via subtyping,
         * and the @table-bound SDL type's child datafetchers read columns by name through
         * the generic {@code Record} interface.
         */
        @Override public DomainReturnType domainReturnType() {
            return new DomainReturnType.Record(returnType.table());
        }
    }

    /**
     * A root query field backed by a developer-provided service method, returning a non-table type.
     *
     * <p>Parameter binding (including context arguments) is fully encoded in
     * {@link MethodRef#params()} via {@link ParamSource}.
     *
     * <p>{@code errorChannel} carries the carrier-side typed-error wiring when this field's
     * payload includes an {@code errors} field. The success arm is a passthrough: the service
     * method returns the SDL payload class (or scalar / pojo) directly, and per-field wiring
     * projects SDL fields off the parent's domain return. This is where the non-table return
     * differs from its table-bound sibling, whose records are key carriers re-selected from the
     * catalog.
     */
    record QueryServiceRecordField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef returnType,
        ServiceMethodCall serviceMethodCall,
        Optional<ErrorChannel.Mapped> errorChannel
    ) implements QueryField, ServiceField, WithErrorChannel {
        /**
         * Forks on {@link #returnType()} exactly as its structural twin
         * {@link MutationField.MutationServiceRecordField#domainReturnType()} does. The two are
         * root {@code @service} producers over one payload result type reached from the two
         * operation roots, and both roots really do reach the same populations (the carrier mint
         * keys on the payload type name, not on the root), so the symmetry is the invariant:
         * writing the fork out on both sides is what keeps one payload type from carrying two
         * arms depending on which root produced it.
         *
         * <ul>
         *   <li>{@link ReturnTypeRef.ResultReturnType}:
         *       {@link DomainReturnType#claimForResultReturn}, so a table-carrying payload answers
         *       {@link DomainReturnType.TableRecord} and a class-backed one the backing-class
         *       claim.</li>
         *   <li>{@link ReturnTypeRef.ScalarReturnType}: {@link DomainReturnType.Plain} over the
         *       reflected peel. A root {@code @service} returns its payload directly rather than
         *       through a container, so {@link OutputField#peelToClassName} is honest here.</li>
         * </ul>
         */
        @Override public DomainReturnType domainReturnType() {
            return switch (returnType) {
                case ReturnTypeRef.ResultReturnType r -> DomainReturnType.claimForResultReturn(r);
                case ReturnTypeRef.ScalarReturnType ignored ->
                    new DomainReturnType.Plain(OutputField.peelToClassName(serviceMethodCall.javaReturnType()));
                // A @table-typed return classifies as QueryServiceTableField and a polymorphic one
                // as QueryServicePolymorphicField; neither arm reaches this leaf.
                case ReturnTypeRef.TableBoundReturnType ignored -> new DomainReturnType.NoClaim();
                case ReturnTypeRef.PolymorphicReturnType ignored -> new DomainReturnType.NoClaim();
            };
        }
    }

    /**
     * A root query field backed by a developer-provided service method that returns a multitable
     * {@link GraphitronType.InterfaceType} over distinct-table participants (route (a)).
     *
     * <p>The service hands back a PK-populated jOOQ {@code TableRecord} per branch. The emitted
     * fetcher dispatches on each returned record's runtime class against the participant set
     * (matching {@link ParticipantRef.TableBound#table()}'s record class), tags the matched
     * participant's {@code __typename}, and auto-fetches the selected columns by PK against that
     * participant's table.
     *
     * <p>Interface only: a {@code @service} returning a union is permanently unsupported (union
     * polymorphism is a generated-query-path capability, rejected at classify time), and a
     * single-table discriminated interface ({@code TableInterfaceType}) routes to the sibling
     * {@link QueryServiceTableInterfaceField} leaf; so this variant only ever carries a
     * distinct-table multitable interface return.
     *
     * <p>{@code participants} is the resolved participant set, attached at the classify site from
     * the interface type (the same source {@link QueryInterfaceField} uses).
     */
    record QueryServicePolymorphicField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef.PolymorphicReturnType returnType,
        List<ParticipantRef> participants,
        ServiceMethodCall serviceMethodCall,
        Optional<ErrorChannel.Mapped> errorChannel
    ) implements QueryField, ServiceField, WithErrorChannel {
        public QueryServicePolymorphicField {
            participants = List.copyOf(participants);
        }
        @Override public DomainReturnType domainReturnType() {
            return new DomainReturnType.NoClaim();
        }
    }

    /**
     * A root {@code @service} field returning a single-table discriminated interface
     * ({@code @table @discriminate}, implementers pinned by {@code @discriminator(value:)}, all
     * sharing one jOOQ table). The single-table sibling of {@link QueryServicePolymorphicField}
     * (route (a)): both carry a service binding and dispatch a service-returned record set to
     * {@code __typename}, but the mechanism differs. Route (a) routes on each record's runtime Java
     * class (distinct-table participants); here every returned record is the same shared-table record,
     * so class dispatch cannot tell the subtypes apart. Instead the emitted fetcher collects the
     * shared table's PKs off the service records, runs one by-PK SELECT projecting the read-side
     * {@code __discriminator__} (plus the unified participant field set and the discriminator-gated
     * cross-table subselects), and lets the per-{@code TableInterfaceType} {@code TypeResolver}
     * route each row off the live discriminator value.
     *
     * <p>Carries the same read-side single-table discrimination data as
     * {@link QueryTableInterfaceField} ({@code returnType} over the shared {@code @table},
     * {@code discriminatorColumn}, {@code knownDiscriminatorValues}, {@code participants} of
     * {@link ParticipantRef.TableBound} with non-null {@code discriminatorValue}) plus the service
     * binding. The payload is a raw {@code Record} / {@code List<Record>} routed to a subtype at
     * run time, so {@link #domainReturnType()} is {@link DomainReturnType.NoClaim}, exactly as
     * route (a)'s variant.
     */
    record QueryServiceTableInterfaceField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef.TableBoundReturnType returnType,
        ColumnRef discriminatorColumn,
        List<String> knownDiscriminatorValues,
        List<ParticipantRef> participants,
        ServiceMethodCall serviceMethodCall,
        Optional<ErrorChannel.Mapped> errorChannel
    ) implements QueryField, ServiceField, WithErrorChannel {
        public QueryServiceTableInterfaceField {
            knownDiscriminatorValues = List.copyOf(knownDiscriminatorValues);
            participants = List.copyOf(participants);
        }
        @Override public DomainReturnType domainReturnType() {
            return new DomainReturnType.NoClaim();
        }
    }
}
