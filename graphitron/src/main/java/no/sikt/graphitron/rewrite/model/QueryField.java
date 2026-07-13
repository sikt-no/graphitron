package no.sikt.graphitron.rewrite.model;

import graphql.language.SourceLocation;
import no.sikt.graphitron.javapoet.ClassName;

import java.util.List;
import java.util.Optional;

/**
 * A field on the {@code Query} type. Read-only. All create a new scope or enter service scope.
 *
 * <p>The three primary SQL-generating query field types ({@link QueryTableField},
 * {@link QueryLookupTableField}, {@link QueryTableInterfaceField}) carry the same three
 * SQL-generation components as their child-field counterparts:
 * <ul>
 *   <li>{@code filters} — WHERE-clause contributions; may be empty.</li>
 *   <li>{@code orderBy} — authoritative ordering; always non-null.</li>
 *   <li>{@code pagination} — Relay pagination args; {@code null} when absent.</li>
 * </ul>
 *
 * <p>Service and table-method fields ({@link QueryServiceTableField},
 * {@link QueryServiceRecordField}, {@link QueryTableMethodTableField}) do not carry these
 * components — the developer-controlled method replaces SQL generation entirely.
 */
public sealed interface QueryField extends RootField
    permits QueryField.QueryLookupTableField, QueryField.QueryTableField,
            QueryField.QueryTableMethodTableField, QueryField.QueryRoutineTableField,
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

    @Override default Operation operation() {
        return switch (this) {
            // Catalog reads: Paginate when the return wrapper is a connection, else Fetch.
            case QueryTableField f -> OutputField.readOperation(f.returnType(), f.filters(), f.orderBy(), f.pagination());
            case QueryTableInterfaceField f -> OutputField.readOperation(f.returnType(), f.filters(), f.orderBy(), f.pagination());
            // Table-method / polymorphic roots carry no field-level filter surface.
            case QueryTableMethodTableField f -> OutputField.readOperation(f.returnType(), List.of(), new OrderBySpec.None(), null);
            // Routine reads are Fetch over the routine-result table; no field-level filter surface day-one.
            case QueryRoutineTableField f -> OutputField.readOperation(f.returnType(), List.of(), new OrderBySpec.None(), null);
            case QueryInterfaceField f -> OutputField.readOperation(f.returnType(), List.of(), new OrderBySpec.None(), null);
            case QueryUnionField f -> OutputField.readOperation(f.returnType(), List.of(), new OrderBySpec.None(), null);
            case QueryLookupTableField f -> new Operation.Lookup(f.lookupMapping());
            case QueryNodeField ignored -> new Operation.NodeResolve();
            case QueryNodesField ignored -> new Operation.NodeResolve();
            case QueryServiceTableField f -> OutputField.serviceCall(f.serviceMethodCall());
            case QueryServiceRecordField f -> OutputField.serviceCall(f.serviceMethodCall());
            case QueryServicePolymorphicField f -> OutputField.serviceCall(f.serviceMethodCall());
            case QueryServiceTableInterfaceField f -> OutputField.serviceCall(f.serviceMethodCall());
        };
    }

    @Override default Target target() {
        return switch (this) {
            // Catalog table reads: wrap(...) keeps the Connection -> Single(Connection) decomposition.
            case QueryTableField f -> OutputField.wrap(f.returnType().wrapper(), new TargetShape.Table());
            case QueryLookupTableField f -> OutputField.wrap(f.returnType().wrapper(), new TargetShape.Table());
            case QueryTableMethodTableField f -> OutputField.wrap(f.returnType().wrapper(), new TargetShape.Table());
            case QueryRoutineTableField f -> OutputField.wrap(f.returnType().wrapper(), new TargetShape.Table());
            case QueryTableInterfaceField f -> OutputField.wrap(f.returnType().wrapper(), new TargetShape.Table());
            case QueryServiceTableField f -> OutputField.wrap(f.returnType().wrapper(), new TargetShape.Table());
            case QueryServiceRecordField f -> OutputField.listOrSingle(f.returnType().wrapper(), new TargetShape.Record());
            // Polymorphic roots are catalog-bound (every participant is a @table/NodeType): the shape is
            // Interface / Union (its payload modeled-but-unpopulated this slice); mapping() derives Table.
            case QueryInterfaceField f -> OutputField.wrap(f.returnType().wrapper(), new TargetShape.Interface());
            case QueryUnionField f -> OutputField.wrap(f.returnType().wrapper(), new TargetShape.Union());
            // Service-polymorphic returns are interface-only (union/table-interface rejected at
            // classify time) and route through the __typename-column TypeResolver.
            case QueryServicePolymorphicField f -> OutputField.wrap(f.returnType().wrapper(), new TargetShape.Interface());
            // Single-table service interface return (R405): payload is a raw Record / List<Record>
            // routed by the discriminated TypeResolver, same wiring shape as route (a). Interface
            // (not Table) keeps requiresReFetch() false so the re-fetch mirror agrees with the
            // service fetcher, which does the by-PK re-projection itself.
            case QueryServiceTableInterfaceField f -> OutputField.wrap(f.returnType().wrapper(), new TargetShape.Interface());
            case QueryNodeField f -> OutputField.wrap(f.returnType().wrapper(), new TargetShape.Interface());
            case QueryNodesField f -> OutputField.wrap(f.returnType().wrapper(), new TargetShape.Interface());
        };
    }

    record QueryLookupTableField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef.TableBoundReturnType returnType,
        List<WhereFilter> filters,
        OrderBySpec orderBy,
        PaginationSpec pagination,
        LookupMapping lookupMapping
    ) implements QueryField, SqlGeneratingField, LookupField {
        /** The name of the generated synchronous lookup helper method. */
        public String lookupMethodName() {
            return "lookup" + Character.toUpperCase(name().charAt(0)) + name().substring(1);
        }
        @Override public DomainReturnType domainReturnType() {
            return new DomainReturnType.Record(returnType.table());
        }
    }

    record QueryTableField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef.TableBoundReturnType returnType,
        List<WhereFilter> filters,
        OrderBySpec orderBy,
        PaginationSpec pagination
    ) implements QueryField, SqlGeneratingField {
        @Override public DomainReturnType domainReturnType() {
            return new DomainReturnType.Record(returnType.table());
        }
    }

    /**
     * A root query field using {@code @tableMethod}. The developer provides a pre-filtered
     * {@code Table<?>}; Graphitron generates a fetcher around it.
     *
     * <p>The method signature is:
     * <pre>
     *     Table&lt;?&gt; method(Table&lt;?&gt; targetTable, arg1, arg2, ...)
     * </pre>
     * where the table parameter has {@link ParamSource.Table} as its source, and subsequent
     * parameters have {@link ParamSource.Arg} or {@link ParamSource.Context}.
     *
     * <p>The return type is always a {@link ReturnTypeRef.TableBoundReturnType}: the
     * directive's whole purpose is to bind a developer-authored jOOQ table method, which by
     * construction returns a generated jOOQ table class.
     * {@link TableMethodDirectiveResolver} rejects any other return shape as a schema error.
     */
    record QueryTableMethodTableField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef.TableBoundReturnType returnType,
        MethodRef method,
        Optional<ErrorChannel> errorChannel
    ) implements QueryField, MethodBackedField, WithErrorChannel {
        @Override public DomainReturnType domainReturnType() {
            return new DomainReturnType.Record(returnType.table());
        }
    }

    /**
     * A root query field whose table chain starts with a jOOQ database routine ({@code @routine}).
     * jOOQ generates a table-valued read function as a catalog {@code Table<R>}, so the return
     * type is always a {@link ReturnTypeRef.TableBoundReturnType} and the existing
     * selection-narrowing projection applies unchanged.
     *
     * <p>R435 re-homed this leaf onto the {@code (start, hops)} chain; R451 extracted that shape
     * into the shared {@link RoutineChain} carrier: {@code start} is the routine node (the
     * {@code FROM} source — the schema's global {@code Routines} convenience method call with IN
     * parameters bound from GraphQL arguments), {@code hops} the {@code @reference}-contributed
     * steps that follow it in authored directive order. The R300 single-node shape is
     * {@code hops = []}, where the routine result is also the terminus. The start is a
     * {@link TableExpr.RoutineCall} rather than a {@link JoinStep}: R333 models {@code on} as
     * absent exactly for the start node, and the carrier encodes that absence structurally
     * instead of widening {@code Hop.on} to an optional (see {@link On}). The shared chain
     * invariants (all-{@code Arg} start bindings, catalog-only non-lateral hops) are pinned in
     * {@link RoutineChain}'s compact constructor, one enforcer spanning this leaf and
     * {@link MutationField.MutationRoutineWriteField}; only the terminus rule stays here (it
     * reads this leaf's return type).
     *
     * <p>{@code target()} projects a bare {@link TargetShape.Table}, exactly as
     * {@link QueryTableMethodTableField} does. See R300 / R435 / R451.
     */
    record QueryRoutineTableField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef.TableBoundReturnType returnType,
        RoutineChain chain
    ) implements QueryField, RoutineChainField {

        public QueryRoutineTableField {
            if (chain == null) {
                throw new NullPointerException("QueryRoutineTableField.chain must not be null");
            }
            // Terminus invariant: the projected @table type is the chain's last node.
            if (!chain.terminus().denotesSameTableAs(returnType.table())) {
                throw new IllegalArgumentException(
                    "QueryRoutineTableField terminus mismatch: the chain ends on '"
                    + chain.terminus().tableName() + "' but the field's @table type is bound to '"
                    + returnType.table().tableName() + "'; the classifier's terminus rule must "
                    + "reject this before construction");
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
            return new DomainReturnType.Plain(OutputField.OBJECT_CLASS);
        }
    }

    record QueryNodesField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef.PolymorphicReturnType returnType
    ) implements QueryField {
        @Override public DomainReturnType domainReturnType() {
            return new DomainReturnType.Plain(OutputField.OBJECT_CLASS);
        }
    }

    record QueryTableInterfaceField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef.TableBoundReturnType returnType,
        String discriminatorColumn,
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
     * per branch and a per-typename batched lookup using {@code <Type>.$fields}.
     */
    record QueryInterfaceField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef.PolymorphicReturnType returnType,
        List<ParticipantRef> participants,
        List<ParticipantFilters> participantFilters
    ) implements QueryField {
        public QueryInterfaceField {
            participants = List.copyOf(participants);
            participantFilters = List.copyOf(participantFilters);
        }
        @Override public DomainReturnType domainReturnType() {
            return new DomainReturnType.Plain(OutputField.OBJECT_CLASS);
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
        List<ParticipantFilters> participantFilters
    ) implements QueryField {
        public QueryUnionField {
            participants = List.copyOf(participants);
            participantFilters = List.copyOf(participantFilters);
        }
        @Override public DomainReturnType domainReturnType() {
            return new DomainReturnType.Plain(OutputField.OBJECT_CLASS);
        }
    }

    /**
     * A root query field backed by a developer-provided service method, returning a table-mapped type.
     *
     * <p>Parameter binding (including context arguments) is fully encoded in
     * {@link MethodRef#params()} via {@link ParamSource}.
     *
     * <p>{@code errorChannel} carries the carrier-side typed-error wiring when this field's
     * payload includes an {@code errors} field. The success arm is universal passthrough: the
     * service method returns the SDL payload class (or table-bound record) directly, and
     * per-field wiring projects SDL fields off the parent's domain return.
     */
    record QueryServiceTableField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef.TableBoundReturnType returnType,
        ServiceMethodCall serviceMethodCall,
        Optional<ErrorChannel> errorChannel
    ) implements QueryField, ServiceField, WithErrorChannel {
        /**
         * R204: see {@link ChildField.ServiceTableField#domainReturnType()} — the typed
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
     * payload includes an {@code errors} field. The success arm is universal passthrough: the
     * service method returns the SDL payload class (or scalar / pojo) directly, and per-field
     * wiring projects SDL fields off the parent's domain return.
     */
    record QueryServiceRecordField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef returnType,
        ServiceMethodCall serviceMethodCall,
        Optional<ErrorChannel> errorChannel
    ) implements QueryField, ServiceField, WithErrorChannel {
        @Override public DomainReturnType domainReturnType() {
            return new DomainReturnType.Plain(OutputField.peelToClassName(serviceMethodCall.javaReturnType()));
        }
    }

    /**
     * A root query field backed by a developer-provided service method that returns a multitable
     * {@link GraphitronType.InterfaceType} over distinct-table participants (R365, route (a)).
     *
     * <p>The service hands back a PK-populated jOOQ {@code TableRecord} per branch. The emitted
     * fetcher dispatches on each returned record's runtime class against the participant set
     * (matching {@link ParticipantRef.TableBound#table()}'s record class), tags the matched
     * participant's {@code __typename}, and auto-fetches the selected columns by PK against that
     * participant's table.
     *
     * <p>Interface only: a {@code @service} returning a union is permanently unsupported (union
     * polymorphism is a generated-query-path capability), and a single-table discriminated interface
     * ({@code TableInterfaceType}) is deferred; both are rejected at classify time, so this variant
     * only ever carries a distinct-table multitable interface return.
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
        Optional<ErrorChannel> errorChannel
    ) implements QueryField, ServiceField, WithErrorChannel {
        public QueryServicePolymorphicField {
            participants = List.copyOf(participants);
        }
        @Override public DomainReturnType domainReturnType() {
            return new DomainReturnType.Plain(OutputField.OBJECT_CLASS);
        }
    }

    /**
     * R405 — a root {@code @service} field returning a single-table discriminated interface
     * ({@code @table @discriminate}, implementers pinned by {@code @discriminator(value:)}, all
     * sharing one jOOQ table). The single-table sibling of {@link QueryServicePolymorphicField}
     * (route (a)): both carry a service binding and dispatch a service-returned record set to
     * {@code __typename}, but the mechanism differs. Route (a) routes on each record's runtime Java
     * class (distinct-table participants); here every returned record is the same shared-table record,
     * so class dispatch cannot tell the subtypes apart. Instead the emitted fetcher collects the
     * shared table's PKs off the service records, runs one by-PK SELECT projecting the read-side
     * {@code __discriminator__} (plus the unified participant field set and discriminator-gated
     * cross-table {@code LEFT JOIN}s), and lets the per-{@code TableInterfaceType} {@code TypeResolver}
     * route each row off the live discriminator value.
     *
     * <p>Carries the same read-side single-table discrimination data as
     * {@link QueryTableInterfaceField} ({@code returnType} over the shared {@code @table},
     * {@code discriminatorColumn}, {@code knownDiscriminatorValues}, {@code participants} of
     * {@link ParticipantRef.TableBound} with non-null {@code discriminatorValue}) plus the service
     * binding. The payload is a raw {@code Record} / {@code List<Record>}, so {@link #domainReturnType()}
     * is {@link DomainReturnType.Plain} over {@code Object}, exactly as route (a)'s variant.
     */
    record QueryServiceTableInterfaceField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef.TableBoundReturnType returnType,
        String discriminatorColumn,
        List<String> knownDiscriminatorValues,
        List<ParticipantRef> participants,
        ServiceMethodCall serviceMethodCall,
        Optional<ErrorChannel> errorChannel
    ) implements QueryField, ServiceField, WithErrorChannel {
        public QueryServiceTableInterfaceField {
            knownDiscriminatorValues = List.copyOf(knownDiscriminatorValues);
            participants = List.copyOf(participants);
        }
        @Override public DomainReturnType domainReturnType() {
            return new DomainReturnType.Plain(OutputField.OBJECT_CLASS);
        }
    }
}
