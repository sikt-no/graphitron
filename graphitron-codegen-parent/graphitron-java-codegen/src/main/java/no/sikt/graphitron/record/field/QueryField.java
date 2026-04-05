package no.sikt.graphitron.record.field;

import graphql.language.SourceLocation;

/**
 * A field on the {@code Query} type. Read-only. All create a new scope or enter private service scope.
 */
public sealed interface QueryField extends RootField
    permits QueryField.LookupQueryField, QueryField.TableQueryField, QueryField.TableMethodQueryField,
            QueryField.NodeQueryField, QueryField.EntityQueryField,
            QueryField.TableInterfaceQueryField, QueryField.InterfaceQueryField, QueryField.UnionQueryField,
            QueryField.ServiceQueryField {

    /**
     * Triggered by {@code @lookupKey} on one or more arguments.
     *
     * <p>{@code returnType} is the resolved outcome of looking up the return type in the classified
     * schema.
     */
    record LookupQueryField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef returnType
    ) implements QueryField {}

    /**
     * A root query field whose return type is annotated with {@code @table}.
     *
     * <p>{@code returnType} is the resolved outcome of looking up the return type in the classified
     * schema, with the {@link FieldWrapper} embedded — {@link FieldWrapper.Single} for a single-item
     * lookup, {@link FieldWrapper.List} for a list result, or {@link FieldWrapper.Connection} for a
     * Relay paginated list. {@link ReturnTypeRef.TableBoundReturnType} carries the
     * {@link no.sikt.graphitron.record.type.TableRef} when the return type's table is resolved —
     * used to detect non-deterministic ordering (list or connection with no {@code @defaultOrder}
     * and a PK-less table). The validator reports errors for unresolved ordering specs on list and
     * connection variants.
     */
    record TableQueryField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef returnType
    ) implements QueryField {}

    /**
     * A root query field using {@code @tableMethod} — the developer provides a pre-filtered {@code Table<?>}.
     *
     * <p>{@code returnType} is the resolved outcome of looking up the return type in the classified
     * schema, with the {@link FieldWrapper} embedded.
     */
    record TableMethodQueryField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef returnType
    ) implements QueryField {}

    /**
     * The {@code Query.node(id:)} field for Relay Global Object Identification.
     *
     * <p>{@code returnType} is the resolved outcome of looking up the return type in the classified
     * schema.
     */
    record NodeQueryField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef returnType
    ) implements QueryField {}

    /**
     * The {@code Query._entities(representations:)} field for Apollo Federation.
     *
     * <p>{@code returnType} is the resolved outcome of looking up the return type in the classified
     * schema.
     */
    record EntityQueryField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef returnType
    ) implements QueryField {}

    /**
     * A root query field whose return type is a single-table interface ({@code @table} + {@code @discriminate}).
     *
     * <p>{@code returnType} is the resolved outcome of looking up the return type in the classified
     * schema, with the {@link FieldWrapper} embedded.
     */
    record TableInterfaceQueryField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef returnType
    ) implements QueryField {}

    /**
     * A root query field whose return type is a multi-table interface.
     *
     * <p>{@code returnType} is the resolved outcome of looking up the return type in the classified
     * schema, with the {@link FieldWrapper} embedded.
     */
    record InterfaceQueryField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef returnType
    ) implements QueryField {}

    /**
     * A root query field whose return type is a union.
     *
     * <p>{@code returnType} is the resolved outcome of looking up the return type in the classified
     * schema, with the {@link FieldWrapper} embedded.
     */
    record UnionQueryField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef returnType
    ) implements QueryField {}

    /**
     * A root query field delegating to a developer-provided service class via {@code @service}.
     *
     * <p>{@code returnType} is the resolved outcome of looking up the return type in the classified
     * schema.
     */
    record ServiceQueryField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef returnType
    ) implements QueryField {}
}
