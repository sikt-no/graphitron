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
     */
    record LookupQueryField(
        String parentTypeName,
        String name,
        SourceLocation location
    ) implements QueryField {}

    /**
     * A root query field whose return type is annotated with {@code @table}.
     *
     * <p>{@code returnTypeName} is the GraphQL type name of the return type (e.g. {@code "Film"}).
     * The validator uses this to look up the return type's jOOQ table and detect non-deterministic
     * ordering (list cardinality with no {@code @defaultOrder} and a PK-less table).
     *
     * <p>{@code cardinality} is the cardinality of this field — {@link FieldCardinality.Single} for a
     * single-item lookup, {@link FieldCardinality.List} for a list result, or
     * {@link FieldCardinality.Connection} for a Relay paginated list. For list and connection variants,
     * {@code defaultOrder} and {@code orderByValues} are carried inside the cardinality record. The
     * validator reports errors for unresolved ordering specs.
     */
    record TableQueryField(
        String parentTypeName,
        String name,
        SourceLocation location,
        String returnTypeName,
        FieldCardinality cardinality
    ) implements QueryField {}

    /**
     * A root query field using {@code @tableMethod} — the developer provides a pre-filtered {@code Table<?>}.
     *
     * <p>{@code cardinality} is the cardinality of this field.
     */
    record TableMethodQueryField(
        String parentTypeName,
        String name,
        SourceLocation location,
        FieldCardinality cardinality
    ) implements QueryField {}

    /**
     * The {@code Query.node(id:)} field for Relay Global Object Identification.
     */
    record NodeQueryField(
        String parentTypeName,
        String name,
        SourceLocation location
    ) implements QueryField {}

    /**
     * The {@code Query._entities(representations:)} field for Apollo Federation.
     */
    record EntityQueryField(
        String parentTypeName,
        String name,
        SourceLocation location
    ) implements QueryField {}

    /**
     * A root query field whose return type is a single-table interface ({@code @table} + {@code @discriminate}).
     *
     * <p>{@code cardinality} is the cardinality of this field.
     */
    record TableInterfaceQueryField(
        String parentTypeName,
        String name,
        SourceLocation location,
        FieldCardinality cardinality
    ) implements QueryField {}

    /**
     * A root query field whose return type is a multi-table interface.
     *
     * <p>{@code cardinality} is the cardinality of this field.
     */
    record InterfaceQueryField(
        String parentTypeName,
        String name,
        SourceLocation location,
        FieldCardinality cardinality
    ) implements QueryField {}

    /**
     * A root query field whose return type is a union.
     *
     * <p>{@code cardinality} is the cardinality of this field.
     */
    record UnionQueryField(
        String parentTypeName,
        String name,
        SourceLocation location,
        FieldCardinality cardinality
    ) implements QueryField {}

    /**
     * A root query field delegating to a developer-provided service class via {@code @service}.
     */
    record ServiceQueryField(
        String parentTypeName,
        String name,
        SourceLocation location
    ) implements QueryField {}
}
