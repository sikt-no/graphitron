package no.sikt.graphitron.record.field;

import graphql.language.SourceLocation;
import no.sikt.graphitron.record.type.NodeRef;
import no.sikt.graphitron.record.field.ColumnRef;
import no.sikt.graphitron.record.field.FieldConditionRef;
import no.sikt.graphitron.record.field.NodeTypeRef;
import no.sikt.graphitron.record.field.ReferencePathElementRef;
import no.sikt.graphitron.record.type.TableRef.ResolvedTable;

import java.util.List;

/**
 * A field on a non-root output type. Source context (table-mapped or result-mapped) is
 * determined by the parent {@link no.sikt.graphitron.record.type.GraphitronType} at generation time.
 */
public sealed interface ChildField extends GraphitronField
    permits ChildField.ColumnField, ChildField.ColumnReferenceField,
            ChildField.NodeIdField, ChildField.NodeIdReferenceField,
            ChildField.TableField, ChildField.TableMethodField,
            ChildField.TableInterfaceField, ChildField.InterfaceField, ChildField.UnionField,
            ChildField.NestingField, ChildField.ConstructorField,
            ChildField.ServiceField, ChildField.ComputedField, ChildField.PropertyField,
            ChildField.MultitableReferenceField {

    /**
     * A scalar or enum field bound to a column on the source table.
     *
     * <p>{@code columnName} is the database column name: the value of {@code @field(name:)} when
     * the directive is present, otherwise the GraphQL field name.
     *
     * <p>{@code column} is the outcome of resolving {@code columnName} against the jOOQ table:
     * {@link ColumnRef.ResolvedColumn} when the column was found, {@link ColumnRef.UnresolvedColumn}
     * when it was not. The {@link no.sikt.graphitron.record.GraphitronSchemaValidator} reports an
     * error for {@code UnresolvedColumn}.
     *
     * <p>{@code javaNamePresent} is {@code true} when the {@code @field(javaName:)} argument was
     * supplied. This argument is not supported in record-based output and the validator reports an
     * error when it is present.
     */
    record ColumnField(
        String parentTypeName,
        String name,
        SourceLocation location,
        String columnName,
        ColumnRef column,
        boolean javaNamePresent
    ) implements ChildField {}

    /**
     * A field bound to a column on a table joined from the source table.
     *
     * <p>{@code columnName} is the database column name: the value of {@code @field(name:)} when
     * the directive is present, otherwise the GraphQL field name.
     *
     * <p>{@code column} is the outcome of resolving {@code columnName} against the jOOQ table:
     * {@link ColumnRef.ResolvedColumn} when the column was found, {@link ColumnRef.UnresolvedColumn}
     * when it was not. The {@link no.sikt.graphitron.record.GraphitronSchemaValidator} reports an
     * error for {@code UnresolvedColumn}.
     *
     * <p>{@code referencePath} is the ordered list of join steps from the source table to the target
     * column's table, extracted from {@code @reference(path:)}. Required — an empty list is a
     * validation error.
     *
     * <p>{@code javaNamePresent} is {@code true} when the {@code @field(javaName:)} argument was
     * supplied. This argument is not supported in record-based output and the validator reports an
     * error when it is present.
     */
    record ColumnReferenceField(
        String parentTypeName,
        String name,
        SourceLocation location,
        String columnName,
        ColumnRef column,
        List<ReferencePathElementRef> referencePath,
        boolean javaNamePresent
    ) implements ChildField {}

    /**
     * An {@code @nodeId} field that encodes a Relay Global ID from the source type's key columns.
     *
     * <p>{@code parentTypeName} is the name of the containing GraphQL type.
     *
     * <p>{@code node} is the parent type's {@code @node} step: a
     * {@link NodeRef.NodeDirective} carrying the optional {@code typeId} and
     * the list of key columns when {@code @node} is present, or
     * {@link NodeRef.NoNode} when it is absent (a validation error).
     */
    record NodeIdField(
        String parentTypeName,
        String name,
        SourceLocation location,
        NodeRef node
    ) implements ChildField {}

    /**
     * An {@code @nodeId(typeName: ...)} field that joins to a target type's table and encodes a
     * Relay Global ID.
     *
     * <p>{@code typeName} is the value of the {@code typeName} argument on the {@code @nodeId}
     * directive (e.g. {@code "Film"}). It identifies which type's {@code @node} key columns are
     * encoded in the ID.
     *
     * <p>{@code targetType} is the outcome of resolving {@code typeName} against the classified
     * schema: {@link ReturnTypeRef.TableBoundReturnType} when the named type exists and is a
     * table-backed type (carrying the table ref for FK and path validation), or
     * {@link ReturnTypeRef.OtherReturnType} otherwise.
     *
     * <p>{@code parentTable} is the resolved table of the containing type, or {@code null} when
     * the parent's table is unresolved. A null parent table skips the implicit FK count check.
     *
     * <p>{@code nodeType} is the outcome of resolving {@code typeName} against the {@code @node}
     * directive: {@link NodeTypeRef.ResolvedNodeType} when the named type exists as a table type
     * with {@code @node} (carrying the directive properties for ID encoding), or
     * {@link NodeTypeRef.UnresolvedNodeType} when it does not. The
     * {@link no.sikt.graphitron.record.GraphitronSchemaValidator} reports an error for
     * {@code UnresolvedNodeType}.
     *
     * <p>{@code referencePath} is the ordered list of join steps from the source table to the target
     * type's table, extracted from {@code @reference(path:)}. May be empty when there is exactly one
     * foreign key between the source and target tables (implicit join). The
     * {@link no.sikt.graphitron.record.GraphitronSchemaValidator} reports an error when the path is
     * empty and there is no foreign key or more than one foreign key between the tables. When both a
     * path and a {@code typeName} are supplied the path must lead to the target type's table.
     */
    record NodeIdReferenceField(
        String parentTypeName,
        String name,
        SourceLocation location,
        String typeName,
        ReturnTypeRef targetType,
        ResolvedTable parentTable,
        NodeTypeRef nodeType,
        List<ReferencePathElementRef> referencePath
    ) implements ChildField {}

    /**
     * A child field whose return type is annotated with {@code @table}.
     *
     * <p>{@code returnType} is the resolved outcome of looking up the return type in the classified
     * schema, with the {@link FieldWrapper} embedded — {@link FieldWrapper.Single} for a 1:1 join,
     * {@link FieldWrapper.List} for a 1:N join, or {@link FieldWrapper.Connection} for a Relay
     * paginated list. The validator reports errors for unresolved ordering specs on list and
     * connection variants.
     *
     * <p>{@code referencePath} is the ordered list of join steps extracted from {@code @reference(path:)},
     * used to override FK auto-inference. Empty when no {@code @reference} directive is present —
     * Graphitron will attempt to infer the foreign key automatically.
     *
     * <p>{@code condition} is the resolved or unresolved field-level {@code @condition} directive, or
     * {@link FieldConditionRef.NoFieldCondition} when no {@code @condition} is present. The validator
     * reports an error for an {@link FieldConditionRef.UnresolvedFieldCondition}.
     *
     * <p>{@code splitQuery} is {@code true} when the {@code @splitQuery} directive is present. This
     * causes Graphitron to use a DataLoader instead of an inline subquery.
     */
    record TableField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef returnType,
        List<ReferencePathElementRef> referencePath,
        FieldConditionRef condition,
        boolean splitQuery,
        List<ArgumentSpec> arguments
    ) implements ChildField {}

    /**
     * A child field using {@code @tableMethod} — the developer provides a pre-filtered {@code Table<?>}.
     *
     * <p>{@code returnType} is the resolved outcome of looking up the return type in the classified
     * schema, with the {@link FieldWrapper} embedded.
     *
     * <p>{@code referencePath} is the ordered list of join steps extracted from {@code @reference(path:)},
     * used to override FK auto-inference. Empty when no {@code @reference} directive is present —
     * Graphitron will attempt to infer the foreign key automatically.
     *
     * <p>{@code tableMethodRef} is the {@code tableMethodReference: ExternalCodeReference!} argument
     * of the {@code @tableMethod} directive — the Java method that returns the pre-filtered table.
     *
     * <p>{@code arguments} is the full list of GraphQL arguments on the field.
     *
     * <p>{@code contextArguments} is the list of strings from the {@code contextArguments} parameter
     * of the {@code @tableMethod} directive.
     */
    record TableMethodField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef returnType,
        List<ReferencePathElementRef> referencePath,
        ExternalRef tableMethodRef,
        List<ArgumentSpec> arguments,
        List<String> contextArguments
    ) implements ChildField {}

    /**
     * A child field whose return type is a single-table interface.
     *
     * <p>{@code returnType} is the resolved outcome of looking up the return type in the classified
     * schema, with the {@link FieldWrapper} embedded.
     */
    record TableInterfaceField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef returnType
    ) implements ChildField {}

    /**
     * A child field whose return type is a multi-table interface.
     *
     * <p>{@code returnType} is the resolved outcome of looking up the return type in the classified
     * schema, with the {@link FieldWrapper} embedded.
     */
    record InterfaceField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef returnType
    ) implements ChildField {}

    /**
     * A child field whose return type is a union.
     *
     * <p>{@code returnType} is the resolved outcome of looking up the return type in the classified
     * schema, with the {@link FieldWrapper} embedded.
     */
    record UnionField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef returnType
    ) implements ChildField {}

    /**
     * A child field that inherits the source table context without introducing a new scope boundary.
     *
     * <p>{@code returnType} is the resolved outcome of looking up the return type in the classified
     * schema.
     */
    record NestingField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef returnType
    ) implements ChildField {}

    /**
     * A child field mapped via a constructor parameter on a result record.
     *
     * <p>{@code returnType} is the resolved outcome of looking up the return type in the classified
     * schema.
     */
    record ConstructorField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef returnType
    ) implements ChildField {}

    /**
     * A child field delegating to a developer-provided service class via {@code @service}.
     *
     * <p>{@code returnType} is the resolved outcome of looking up the return type in the classified
     * schema.
     *
     * <p>{@code referencePath} is the ordered list of join steps extracted from {@code @reference(path:)},
     * providing the lift condition that reconnects this field's result back to the parent table.
     * Each element should carry a {@code condition} method — no FK is involved in lift conditions.
     * Empty when no {@code @reference} directive is present.
     *
     * <p>{@code serviceRef} is the {@code service: ExternalCodeReference!} argument of the
     * {@code @service} directive — the Java class and method to delegate to.
     *
     * <p>{@code contextArguments} is the list of strings from the {@code contextArguments} parameter
     * of the {@code @service} directive.
     *
     * <p>{@code arguments} is the full list of GraphQL arguments on the field.
     */
    record ServiceField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef returnType,
        List<ReferencePathElementRef> referencePath,
        ExternalRef serviceRef,
        List<ArgumentSpec> arguments,
        List<String> contextArguments
    ) implements ChildField {}

    /**
     * A child field resolved by a developer-provided jOOQ {@code Field<?>} expression via {@code @externalField}.
     *
     * <p>{@code returnType} is the resolved outcome of looking up the return type in the classified
     * schema.
     *
     * <p>{@code referencePath} is the ordered list of join steps extracted from {@code @reference(path:)},
     * providing the lift condition that reconnects this field's result back to the parent table.
     * Each element should carry a {@code condition} method — no FK is involved in lift conditions.
     * Empty when no {@code @reference} directive is present.
     */
    record ComputedField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef returnType,
        List<ReferencePathElementRef> referencePath
    ) implements ChildField {}

    /**
     * A scalar or nested property read from a result-mapped source. No SQL generated.
     *
     * <p>{@code columnName} is the property name used when accessing the source record:
     * the value of {@code @field(name:)} when present, otherwise the GraphQL field name.
     */
    record PropertyField(
        String parentTypeName,
        String name,
        SourceLocation location,
        String columnName
    ) implements ChildField {}

    /**
     * A field annotated with {@code @multitableReference}.
     *
     * <p>This directive is not supported in record-based output. The
     * {@link no.sikt.graphitron.record.GraphitronSchemaValidator} reports an error for every field
     * classified here.
     */
    record MultitableReferenceField(
        String parentTypeName,
        String name,
        SourceLocation location
    ) implements ChildField {}
}
