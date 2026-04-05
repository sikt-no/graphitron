package no.sikt.graphitron.record.field;

import graphql.language.SourceLocation;

import java.util.List;

/**
 * A field on the {@code Query} type. Read-only. All create a new scope or enter private service scope.
 */
public sealed interface QueryField extends RootField
    permits QueryField.LookupQueryField, QueryField.TableQueryField, QueryField.TableMethodQueryField,
            QueryField.NodeQueryField, QueryField.EntityQueryField,
            QueryField.TableInterfaceQueryField, QueryField.InterfaceQueryField, QueryField.UnionQueryField,
            QueryField.ServiceQueryField {

    /**
     * Triggered by {@code @lookupKey} on one or more arguments (including nested inside input types).
     *
     * <p>All arguments participate equally in lookup semantics: list arguments are positionally
     * correlated (must all be the same length), and scalar arguments are broadcast (replicated to
     * fill the batch). The {@code @lookupKey} directive is a field-level classifier only — there is
     * no per-argument semantic distinction between arguments that carry it and those that do not.
     *
     * <p>{@code returnType} is the resolved outcome of looking up the return type in the classified
     * schema. Must carry a {@link FieldWrapper.Single} wrapper — lookup fields return one result per
     * key. The validator reports an error for list or connection wrappers.
     *
     * <p>{@code arguments} is the full list of arguments on the field. The validator rejects any
     * argument with {@code orderBy=true} or {@code conditionArg=true}, as those directives are
     * incompatible with lookup semantics.
     */
    record LookupQueryField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef returnType,
        List<ArgumentSpec> arguments
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
     *
     * <p>{@code arguments} is the full list of arguments on the field (e.g. {@code @orderBy},
     * {@code @condition}, pagination arguments). The validator checks that any referenced input
     * types exist in the classified schema.
     */
    record TableQueryField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef returnType,
        List<ArgumentSpec> arguments
    ) implements QueryField {}

    /**
     * A root query field using {@code @tableMethod} — the developer provides a pre-filtered {@code Table<?>}.
     *
     * <p>{@code returnType} is the resolved outcome of looking up the return type in the classified
     * schema, with the {@link FieldWrapper} embedded.
     *
     * <p>{@code tableMethodRef} is the {@code tableMethodReference: ExternalCodeReference!} argument
     * of the {@code @tableMethod} directive — the Java method that returns the pre-filtered table.
     *
     * <p>{@code contextArguments} is the list of strings from the {@code contextArguments} parameter
     * of the {@code @tableMethod} directive.
     *
     * <p>{@code arguments} is the full list of GraphQL arguments on the field.
     */
    record TableMethodQueryField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef returnType,
        ExternalRef tableMethodRef,
        List<String> contextArguments,
        List<ArgumentSpec> arguments
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
     *
     * <p>{@code arguments} is the full list of arguments on the field. The validator checks that
     * any referenced input types exist in the classified schema.
     *
     * <p>{@code serviceRef} is the {@code service: ExternalCodeReference!} argument of the
     * {@code @service} directive — the Java class and method to delegate to.
     *
     * <p>{@code contextArguments} is the list of strings from the {@code contextArguments} parameter
     * of the {@code @service} directive.
     *
     * <p>{@code arguments} is the full list of GraphQL arguments on the field.
     */
    record ServiceQueryField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef returnType,
        ExternalRef serviceRef,
        List<ArgumentSpec> arguments,
        List<String> contextArguments
    ) implements QueryField {}
}
