package no.sikt.graphitron.rewrite.field;

import graphql.language.SourceLocation;

import java.util.List;

import no.sikt.graphitron.rewrite.field.ArgumentRef;
import no.sikt.graphitron.rewrite.field.ServiceMethodRef;

/**
 * A field on the {@code Query} type. Read-only. All create a new scope or enter private service scope.
 */
public sealed interface QueryField extends RootField
    permits QueryField.QueryLookupTableField, QueryField.QueryTableField, QueryField.QueryTableMethodTableField,
            QueryField.QueryNodeField, QueryField.QueryEntityField,
            QueryField.QueryTableInterfaceField, QueryField.QueryInterfaceField, QueryField.QueryUnionField,
            QueryField.QueryServiceTableField, QueryField.QueryServiceRecordField {

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
     * <p>{@code arguments} is the full list of arguments on the field. The validator rejects
     * {@link ArgumentRef.InputTypeArg.OrderByArg} and {@link ArgumentRef.UnclassifiedArg} as
     * incompatible with lookup semantics.
     */
    /**
     * @param arguments all arguments classified into their resolved state. Each argument is
     *     exactly one {@link ArgumentRef} variant: {@link ArgumentRef.InputTypeArg.TableInputTypeArg}
     *     feeds the input-type lookup code path; {@link ArgumentRef.InputTypeArg.OrderByArg} is a
     *     validator error on lookup fields; {@link ArgumentRef.InputTypeArg.PlainInputTypeArg} is
     *     an unresolved input type (error handled at type level);
     *     {@link ArgumentRef.ScalarArg.ColumnArg} and {@link ArgumentRef.ScalarArg.UnboundScalarArg}
     *     represent scalar/list arguments with and without a resolved database column;
     *     {@link ArgumentRef.ScalarArg.ParamArg} covers non-column-bound method parameters;
     *     {@link ArgumentRef.UnclassifiedArg} marks arguments carrying unsupported directives.
     */
    record QueryLookupTableField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef.TableBoundReturnType returnType,
        List<ArgumentRef> arguments
    ) implements QueryField {}

    /**
     * A root query field whose return type is annotated with {@code @table}.
     *
     * <p>{@code returnType} is the resolved outcome of looking up the return type in the classified
     * schema, with the {@link FieldWrapper} embedded — {@link FieldWrapper.Single} for a single-item
     * lookup, {@link FieldWrapper.List} for a list result, or {@link FieldWrapper.Connection} for a
     * Relay paginated list. {@link ReturnTypeRef.TableBoundReturnType} carries the
     * {@link no.sikt.graphitron.rewrite.type.TableRef} when the return type's table is resolved —
     * used to detect non-deterministic ordering (list or connection with no {@code @defaultOrder}
     * and a PK-less table). The validator reports errors for unresolved ordering specs on list and
     * connection variants.
     *
     * <p>{@code arguments} is the full list of arguments on the field (e.g. {@code @orderBy},
     * {@code @condition}, pagination arguments). The validator checks that any referenced input
     * types exist in the classified schema.
     */
    record QueryTableField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef.TableBoundReturnType returnType,
        List<ArgumentRef> arguments
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
     * <p>{@code arguments} is the full list of GraphQL arguments on the field.
     *
     * <p>{@code contextArguments} is the list of strings from the {@code contextArguments} parameter
     * of the {@code @tableMethod} directive.
     */
    record QueryTableMethodTableField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef.TableBoundReturnType returnType,
        ExternalRef tableMethodRef,
        List<ArgumentRef> arguments,
        List<String> contextArguments
    ) implements QueryField {}

    /**
     * The {@code Query.node(id:)} field for Relay Global Object Identification.
     *
     * <p>{@code returnType} is the resolved outcome of looking up the return type in the classified
     * schema.
     */
    record QueryNodeField(
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
    record QueryEntityField(
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
    record QueryTableInterfaceField(
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
    record QueryInterfaceField(
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
    record QueryUnionField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef returnType
    ) implements QueryField {}

    /**
     * A root query field delegating to a developer-provided service class via {@code @service},
     * where the return type is annotated with {@code @table} (service → table-mapped target).
     *
     * <p>{@code returnType} is narrowed to {@link ReturnTypeRef.TableBoundReturnType}.
     *
     * <p>{@code serviceRef} is the {@code service: ExternalCodeReference!} argument of the
     * {@code @service} directive — the Java class and method to delegate to.
     *
     * <p>{@code arguments} is the full list of GraphQL arguments on the field.
     *
     * <p>{@code contextArguments} is the list of strings from the {@code contextArguments} parameter
     * of the {@code @service} directive.
     *
     * <p>{@code serviceMethodRef} is the resolved outcome of reflecting the service class and method
     * at parse time. {@link ServiceMethodRef.Resolved} when the method could be located;
     * {@link ServiceMethodRef.Unresolved} otherwise.
     */
    record QueryServiceTableField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef.TableBoundReturnType returnType,
        ExternalRef serviceRef,
        List<ArgumentRef> arguments,
        List<String> contextArguments,
        ServiceMethodRef serviceMethodRef
    ) implements QueryField {}

    /**
     * A root query field delegating to a developer-provided service class via {@code @service},
     * where the return type is NOT table-mapped (service → record/scalar target).
     *
     * <p>{@code serviceRef}, {@code arguments}, and {@code contextArguments} have the same semantics
     * as {@link QueryServiceTableField}.
     */
    record QueryServiceRecordField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef returnType,
        ExternalRef serviceRef,
        List<ArgumentRef> arguments,
        List<String> contextArguments
    ) implements QueryField {}
}
