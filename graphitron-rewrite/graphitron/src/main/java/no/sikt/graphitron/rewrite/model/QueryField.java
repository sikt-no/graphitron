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
            QueryField.QueryTableMethodTableField,
            QueryField.QueryNodeField, QueryField.QueryNodesField,
            QueryField.QueryTableInterfaceField, QueryField.QueryInterfaceField,
            QueryField.QueryUnionField,
            QueryField.QueryServiceTableField, QueryField.QueryServiceRecordField {

    /** Every {@code QueryField} leaf is on the {@code Query} root, so the source is {@link Source.Root.Query}. */
    @Override default Source source() { return new Source.Root.Query(); }

    @Override default Operation operation() {
        return switch (this) {
            // Catalog reads: Paginate when the return wrapper is a connection, else Fetch.
            case QueryTableField f -> OutputField.readOperation(f.returnType(), f.filters(), f.orderBy(), f.pagination());
            case QueryTableInterfaceField f -> OutputField.readOperation(f.returnType(), f.filters(), f.orderBy(), f.pagination());
            // Table-method / polymorphic roots carry no field-level filter surface.
            case QueryTableMethodTableField f -> OutputField.readOperation(f.returnType(), List.of(), new OrderBySpec.None(), null);
            case QueryInterfaceField f -> OutputField.readOperation(f.returnType(), List.of(), new OrderBySpec.None(), null);
            case QueryUnionField f -> OutputField.readOperation(f.returnType(), List.of(), new OrderBySpec.None(), null);
            case QueryLookupTableField f -> new Operation.Lookup(f.lookupMapping());
            case QueryNodeField ignored -> new Operation.NodeResolve();
            case QueryNodesField ignored -> new Operation.NodeResolve();
            case QueryServiceTableField f -> OutputField.serviceCall(f.serviceMethodCall());
            case QueryServiceRecordField f -> OutputField.serviceCall(f.serviceMethodCall());
        };
    }

    @Override default Mapping mapping() {
        return switch (this) {
            case QueryTableField f -> OutputField.tableMapping(f.returnType());
            case QueryLookupTableField f -> OutputField.tableMapping(f.returnType());
            case QueryTableMethodTableField f -> OutputField.tableMapping(f.returnType());
            case QueryTableInterfaceField f -> OutputField.tableMapping(f.returnType());
            case QueryServiceTableField f -> OutputField.tableMapping(f.returnType());
            case QueryServiceRecordField ignored -> Mapping.Record;
            // Polymorphic roots are catalog-bound (every participant is a @table/NodeType): mapping is
            // Table, with the participant set as a derived slot.
            case QueryInterfaceField f -> OutputField.polyMapping(f.returnType());
            case QueryUnionField f -> OutputField.polyMapping(f.returnType());
            case QueryNodeField f -> OutputField.polyMapping(f.returnType());
            case QueryNodesField f -> OutputField.polyMapping(f.returnType());
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
        List<ParticipantRef> participants
    ) implements QueryField {
        public QueryInterfaceField {
            participants = List.copyOf(participants);
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
        List<ParticipantRef> participants
    ) implements QueryField {
        public QueryUnionField {
            participants = List.copyOf(participants);
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
}
