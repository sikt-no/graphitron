package no.sikt.graphitron.rewrite.model;

import graphql.language.SourceLocation;
import no.sikt.graphitron.rewrite.model.TableRef;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.JoinStep;
import no.sikt.graphitron.rewrite.model.MethodRef;
import no.sikt.graphitron.rewrite.model.ArgumentRef;

import java.util.List;

/**
 * A field on a non-root output type. Source context (table-mapped or result-mapped) is
 * determined by the parent {@link no.sikt.graphitron.rewrite.model.GraphitronType} at generation time.
 */
public sealed interface ChildField extends GraphitronField
    permits ChildField.ColumnField, ChildField.ColumnReferenceField,
            ChildField.NodeIdField, ChildField.NodeIdReferenceField,
            ChildField.TableTargetField,
            ChildField.TableMethodField,
            ChildField.InterfaceField, ChildField.UnionField,
            ChildField.NestingField, ChildField.ConstructorField,
            ChildField.ServiceRecordField,
            ChildField.RecordField,
            ChildField.ComputedField, ChildField.PropertyField,
            ChildField.MultitableReferenceField {

    /**
     * The resolved {@code @condition} directive.
     *
     * <p>The {@code @condition} directive appears at three distinct GraphQL locations, each
     * producing different generated code. This record is shared across all three, but the
     * enclosing context determines which location it represents:
     *
     * <ol>
     *   <li><b>{@code FIELD_DEFINITION}</b> — {@code @condition} on the field itself (e.g.
     *       {@code films: [Film] @condition(...)}). Represented by {@code condition} on
     *       {@link TableTargetField} sub-types. The condition method receives the target table
     *       alias followed by all GraphQL field arguments as positional parameters:
     *       {@code method(targetTable, arg1, arg2, ...)}.
     *       Without {@code override: true}, the generated WHERE clause ANDs the condition call
     *       after all argument column-equality predicates.
     *       With {@code override: true}, all column-equality predicates from arguments are
     *       suppressed; only argument-level condition method calls (if any) survive alongside
     *       the field-level call.</li>
     *
     *   <li><b>{@code ARGUMENT_DEFINITION}</b> — {@code @condition} on a scalar or input-type
     *       argument directly (e.g. {@code query(email: String! @condition(...)): CustomerTable}).
     *       Not yet modelled — to be added as a {@code condition} component on
     *       {@link ArgumentRef.TableArg.ColumnFilterArg} and
     *       {@link ArgumentRef.TableArg.InputFilterArg}.
     *       The condition method receives the target table alias followed by the resolved value(s)
     *       of that argument: {@code method(targetTable, argValue)} for scalars, or
     *       {@code method(targetTable, leaf1, leaf2, ...)} for input types (flattened leaf scalars).
     *       Without {@code override: true}, the normal column-equality predicate is generated
     *       alongside the condition call. With {@code override: true}, only the condition call is
     *       generated for that argument — its column-equality predicate is suppressed.
     *       Other arguments are unaffected by this flag.</li>
     *
     *   <li><b>{@code INPUT_FIELD_DEFINITION}</b> — {@code @condition} on a leaf field inside an
     *       {@code input} type (e.g. {@code input NameInput \{ firstname: String @condition(...) \}}).
     *       Handled during input-type classification; same override semantics as
     *       {@code ARGUMENT_DEFINITION} but scoped to the individual input field.</li>
     * </ol>
     *
     * <p>{@code method} is the resolved condition method. {@code override} controls suppression
     * of the default column-equality predicate for the owning argument or, at field level,
     * suppression of all argument column-equality predicates. {@code contextArgs} lists the names
     * of GraphQL context keys whose runtime values are appended to the condition method's parameter
     * list after the argument value(s).
     *
     * <p>A {@code null} {@code condition} component on the enclosing record means no
     * {@code @condition} directive is present.
     */
    record FieldCondition(
        MethodRef method,
        boolean override,
        List<String> contextArgs
    ) {}

    /**
     * A scalar or enum field bound to a column on the source table.
     *
     * <p>{@code columnName} is the database column name: the value of {@code @field(name:)} when
     * the directive is present, otherwise the GraphQL field name.
     *
     * <p>{@code column} is the resolved column in the jOOQ table. When the column name cannot be
     * matched, the builder returns an
     * {@link no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField} instead of
     * constructing a {@code ColumnField}.
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
     * <p>{@code column} is the resolved column in the joined jOOQ table. When the column name
     * cannot be matched (or any reference path element is unresolved), the builder returns an
     * {@link no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField} instead of
     * constructing a {@code ColumnReferenceField}.
     *
     * <p>{@code joinPath} is the ordered list of join steps from the source table to the target
     * column's table, extracted from {@code @reference(path:)}. Required — an empty list is a
     * validation error. All elements are guaranteed to be resolved (the builder rejects unresolved
     * path elements at classification time).
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
        List<JoinStep> joinPath,
        boolean javaNamePresent
    ) implements ChildField {}

    /**
     * An {@code @nodeId} field that encodes a Relay Global ID from the source type's key columns.
     *
     * <p>Only constructed when the containing type carries {@code @node}. When {@code @nodeId}
     * appears on a type without {@code @node}, the builder returns an
     * {@link no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField} instead.
     *
     * <p>{@code nodeTypeId} is the value of the {@code typeId} argument on the {@code @node}
     * directive of the parent type, or {@code null} when the argument was omitted.
     *
     * <p>{@code nodeKeyColumns} is the resolved list of {@code keyColumns} from the parent type's
     * {@code @node} directive. An empty list means the argument was omitted, in which case the
     * primary key is used at code-generation time.
     */
    record NodeIdField(
        String parentTypeName,
        String name,
        SourceLocation location,
        String nodeTypeId,
        List<ColumnRef> nodeKeyColumns
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
     * table-backed type (carrying the table ref for FK and path validation),
     * {@link ReturnTypeRef.ResultReturnType} for {@code @record}-annotated types, or
     * {@link ReturnTypeRef.ScalarReturnType} otherwise.
     *
     * <p>{@code parentTable} is the resolved table of the containing type, or {@code null} when
     * the parent type is not table-backed. A null parent table skips the implicit FK count check.
     *
     * <p>{@code nodeTypeId} is the value of the {@code typeId} argument on the {@code @node}
     * directive of the target type, or {@code null} when the argument was omitted.
     *
     * <p>{@code nodeKeyColumns} is the resolved list of {@code keyColumns} from the target type's
     * {@code @node} directive used for Relay Global ID encoding. An empty list means the argument
     * was omitted, in which case the primary key is used at code-generation time. Only constructed
     * when the named type exists and carries {@code @node}; otherwise the builder returns an
     * {@link no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField}.
     *
     * <p>{@code joinPath} is the ordered list of join steps from the source table to the target
     * type's table, extracted from {@code @reference(path:)}. May be empty when there is exactly one
     * foreign key between the source and target tables (implicit join). The
     * {@link no.sikt.graphitron.rewrite.GraphitronSchemaValidator} reports an error when the path is
     * empty and there is no foreign key or more than one foreign key between the tables. When both a
     * path and a {@code typeName} are supplied the path must lead to the target type's table. All
     * elements are guaranteed to be resolved (the builder rejects unresolved path elements at
     * classification time).
     */
    record NodeIdReferenceField(
        String parentTypeName,
        String name,
        SourceLocation location,
        String typeName,
        ReturnTypeRef targetType,
        TableRef parentTable,
        String nodeTypeId,
        List<ColumnRef> nodeKeyColumns,
        List<JoinStep> joinPath
    ) implements ChildField {}

    /**
     * A child field whose result type is annotated with {@code @table}.
     *
     * <p>Mirrors {@link no.sikt.graphitron.rewrite.model.GraphitronType.TableBackedType} on the type
     * side. All sub-types navigate from the parent context to a new table scope via an optional join
     * path, and carry a fully resolved {@link ReturnTypeRef.TableBoundReturnType}.
     *
     * <p>{@link NestingField} is intentionally excluded: it carries a {@link ReturnTypeRef.TableBoundReturnType}
     * but does not navigate — it inherits the parent's table context unchanged.
     */
    sealed interface TableTargetField extends ChildField
        permits ChildField.TableField, ChildField.SplitTableField,
                ChildField.LookupTableField, ChildField.SplitLookupTableField,
                ChildField.TableInterfaceField,
                ChildField.ServiceTableField,
                ChildField.RecordTableField, ChildField.RecordLookupTableField {

        ReturnTypeRef.TableBoundReturnType returnType();
        List<JoinStep> joinPath();
        FieldCondition condition();
    }

    /**
     * A child field whose return type is annotated with {@code @table} — inline join or DataLoader
     * depending on source context. No {@code @splitQuery}, no {@code @lookupKey}.
     *
     * <p>{@code returnType} is the resolved outcome of looking up the return type in the classified
     * schema, with the {@link FieldWrapper} embedded — {@link FieldWrapper.Single} for a 1:1 join,
     * {@link FieldWrapper.List} for a 1:N join, or {@link FieldWrapper.Connection} for a Relay
     * paginated list. The validator reports errors for unresolved ordering specs on list and
     * connection variants.
     *
     * <p>{@code joinPath} is the ordered list of join steps extracted from {@code @reference(path:)},
     * used to override FK auto-inference. Empty when no {@code @reference} directive is present —
     * Graphitron will attempt to infer the foreign key automatically.
     *
     * <p>{@code condition} is the resolved field-level {@code @condition} directive, or
     * {@code null} when no {@code @condition} is present. When the condition directive references
     * a method that cannot be reflected, the containing field is classified as
     * {@link UnclassifiedField} by the builder and does not appear here.
     *
     * <p>On a table-mapped parent this generates an inline SQL JOIN.
     */
    record TableField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef.TableBoundReturnType returnType,
        List<JoinStep> joinPath,
        FieldCondition condition,
        List<ArgumentRef> arguments
    ) implements TableTargetField {}

    /**
     * A child field whose return type is annotated with {@code @table} and which carries
     * {@code @splitQuery} — always a DataLoader, regardless of source context.
     *
     * <p>Identical to {@link TableField} in structure, but classified separately because
     * {@code @splitQuery} forces a new scope via DataLoader even when the parent is table-mapped.
     * Without {@code @splitQuery} a table-mapped parent would generate an inline JOIN instead.
     *
     * <p>{@code condition} is the resolved field-level {@code @condition} directive, or
     * {@code null} when no {@code @condition} is present.
     */
    record SplitTableField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef.TableBoundReturnType returnType,
        List<JoinStep> joinPath,
        FieldCondition condition,
        List<ArgumentRef> arguments
    ) implements TableTargetField {}

    /**
     * A child field whose arguments carry {@code @lookupKey} — no {@code @splitQuery}.
     *
     * <p>Classified when {@code @lookupKey} appears on any argument (including nested inside input
     * types) and {@code @splitQuery} is absent. The lookup invariant applies: the result count is
     * always exactly N × M (N parent source rows × M lookup argument values), which blocks
     * {@code @condition} and connection-cardinality returns.
     *
     * <p>On a table-mapped parent this generates a correlated multiset subquery inlined in the
     * parent SELECT (derived target table only, no DataLoader). On a result-mapped parent it starts
     * a new scope via DataLoader using both a derived source table (N parent rows) and the derived
     * target table (M lookup rows, identical for all N).
     *
     * <p>{@code returnType} carries the resolved return type with its {@link FieldWrapper}. Only
     * {@link FieldWrapper.Single} and {@link FieldWrapper.List} are valid; the validator rejects
     * {@link FieldWrapper.Connection}.
     *
     * <p>{@code joinPath} and {@code arguments} have the same semantics as {@link TableField}.
     */
    record LookupTableField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef.TableBoundReturnType returnType,
        List<JoinStep> joinPath,
        FieldCondition condition,
        List<ArgumentRef> arguments
    ) implements TableTargetField {}

    /**
     * A child field whose arguments carry {@code @lookupKey} and which also carries
     * {@code @splitQuery} — always a DataLoader, regardless of source context.
     *
     * <p>Identical to {@link LookupTableField} in structure, but classified separately because
     * {@code @splitQuery} forces a DataLoader even when the parent is table-mapped, producing
     * a full N-source × M-lookup DataLoader with both a derived source table and a derived target
     * table. Without {@code @splitQuery} a table-mapped parent would instead inline a correlated
     * multiset subquery.
     *
     * <p>{@code returnType}, {@code joinPath}, and {@code arguments} have the same semantics
     * as {@link LookupTableField}.
     */
    record SplitLookupTableField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef.TableBoundReturnType returnType,
        List<JoinStep> joinPath,
        FieldCondition condition,
        List<ArgumentRef> arguments
    ) implements TableTargetField {}

    /**
     * A child field using {@code @tableMethod} — the developer provides a pre-filtered {@code Table<?>}.
     *
     * <p>{@code returnType} is the resolved outcome of looking up the return type in the classified
     * schema, with the {@link FieldWrapper} embedded.
     *
     * <p>{@code joinPath} is the ordered list of join steps extracted from {@code @reference(path:)},
     * used to override FK auto-inference. Empty when no {@code @reference} directive is present —
     * Graphitron will attempt to infer the foreign key automatically.
     *
     * <p>{@code tableMethodClassName} and {@code tableMethodMethodName} are from the
     * {@code tableMethodReference: ExternalCodeReference!} argument of the {@code @tableMethod}
     * directive — the Java class and method that return the pre-filtered table.
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
        List<JoinStep> joinPath,
        String tableMethodClassName,
        String tableMethodMethodName,
        List<ArgumentRef> arguments,
        List<String> contextArguments
    ) implements ChildField {}

    /**
     * A child field whose return type is a single-table interface ({@code @table} + {@code @discriminate}).
     *
     * <p>{@code returnType} is the resolved outcome of looking up the return type in the classified
     * schema, with the {@link FieldWrapper} embedded.
     *
     * <p>{@code joinPath} is the ordered list of join steps from {@code @reference(path:)},
     * used to override FK auto-inference when navigating from the parent table to the shared
     * discriminator table. Empty when no {@code @reference} directive is present.
     */
    record TableInterfaceField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef.TableBoundReturnType returnType,
        List<JoinStep> joinPath,
        FieldCondition condition
    ) implements TableTargetField {}

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
        ReturnTypeRef.PolymorphicReturnType returnType
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
        ReturnTypeRef.PolymorphicReturnType returnType
    ) implements ChildField {}

    /**
     * A child field that inherits the source table context without introducing a new scope boundary.
     *
     * <p>{@code returnType} carries the same {@link ReturnTypeRef.TableBoundReturnType} as the
     * parent type — the nesting introduces a GraphQL grouping layer without changing the SQL table
     * context.
     */
    record NestingField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef.TableBoundReturnType returnType
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
     * A child field delegating to a developer-provided service class via {@code @service}, where
     * the return type is annotated with {@code @table} (source → table-mapped target).
     *
     * <p>Graphitron generates a DataLoader-based data fetcher. The parent type may be either
     * table-mapped or result-mapped — the service method provides the SQL separately.
     *
     * <p>{@code returnType} is narrowed to {@link ReturnTypeRef.TableBoundReturnType}; the
     * classified schema guarantees that it resolved to a table-backed type.
     *
     * <p>{@code joinPath} is the ordered list of join steps from {@code @reference(path:)},
     * providing lift conditions that reconnect results back to the parent. Each element should
     * carry a {@code condition} method — no FK is involved. Empty when {@code @reference} is absent.
     *
     * <p>{@code serviceMethodRef} carries the class name, method name, and reflected parameter
     * list of the service method, captured at parse time. If reflection failed the containing
     * field is classified as {@link UnclassifiedField} by the builder and does not appear here.
     *
     * <p>{@code arguments} is the full list of GraphQL arguments on the field.
     *
     * <p>{@code contextArguments} is the list of strings from the {@code contextArguments} parameter
     * of the {@code @service} directive.
     */
    record ServiceTableField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef.TableBoundReturnType returnType,
        List<JoinStep> joinPath,
        FieldCondition condition,
        List<ArgumentRef> arguments,
        List<String> contextArguments,
        MethodRef method
    ) implements TableTargetField {}

    /**
     * A child field delegating to a developer-provided service class via {@code @service}, where
     * the return type is NOT table-mapped (source → record/scalar target).
     *
     * <p>No DataLoader is generated. The service method is called directly from the data fetcher.
     * Validation confirms the reflected method signature matches the declared arguments and context
     * keys.
     *
     * <p>{@code joinPath}, {@code arguments}, {@code contextArguments},
     * and {@code serviceMethodRef} have the same semantics as {@link ServiceTableField}.
     */
    record ServiceRecordField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef returnType,
        List<JoinStep> joinPath,
        List<ArgumentRef> arguments,
        List<String> contextArguments,
        MethodRef method
    ) implements ChildField {}

    /**
     * A child field on a result-mapped parent whose return type is annotated with {@code @table} —
     * always starts a new DataLoader scope (result-mapped → table-mapped target).
     *
     * <p>Classified when a field on a {@code @record} type returns a {@code @table}-annotated type,
     * with no {@code @lookupKey} on its arguments. The generator materialises the parent record rows
     * as a derived source table and opens a new SQL scope joined to the target table.
     *
     * <p>{@code returnType} is narrowed to {@link ReturnTypeRef.TableBoundReturnType}.
     *
     * <p>{@code joinPath} has the same semantics as {@link TableField}: it is the chain of FK/condition
     * steps connecting the parent record's <em>backing table</em> to the target type's table. An empty
     * path means implicit FK inference — the generator must resolve the single FK between the record's
     * backing table and the target table at code-generation time, just as it would for a {@link TableField}
     * with an empty path.
     *
     * <p><b>Build-time connectivity validation gap:</b> the builder currently passes {@code null} as the
     * source table when parsing {@code joinPath} for {@code RecordTableField}, so FK connectivity
     * validation is skipped. This is because the record type's backing table is not yet tracked in
     * {@link no.sikt.graphitron.rewrite.model.GraphitronType}. Once it is, the call site in
     * {@code GraphitronSchemaBuilder.classifyChildFieldOnResultType} should be updated to pass the
     * backing table SQL name, matching what the {@code @table}-parent call sites already do.
     *
     * <p>{@code condition} and {@code arguments} have the same semantics as {@link TableField}.
     */
    record RecordTableField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef.TableBoundReturnType returnType,
        List<JoinStep> joinPath,
        FieldCondition condition,
        List<ArgumentRef> arguments
    ) implements TableTargetField {}

    /**
     * A child field on a result-mapped parent whose arguments carry {@code @lookupKey} and whose
     * return type is annotated with {@code @table} (result-mapped → table-mapped target, lookup).
     *
     * <p>Classified when a field on a {@code @record} type returns a {@code @table}-annotated type
     * and any argument carries {@code @lookupKey}.
     *
     * <p>{@code returnType} is narrowed to {@link ReturnTypeRef.TableBoundReturnType}.
     *
     * <p>{@code joinPath} and {@code arguments} have the same semantics as {@link LookupTableField}.
     */
    record RecordLookupTableField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef.TableBoundReturnType returnType,
        List<JoinStep> joinPath,
        FieldCondition condition,
        List<ArgumentRef> arguments
    ) implements TableTargetField {}

    /**
     * A child field on a result-mapped parent whose return type is itself result-mapped —
     * nested record access (result-mapped → result-mapped target).
     *
     * <p>Classified when a field on a {@code @record} type returns another {@code @record} type
     * (or any non-table, non-scalar return). The generator accesses the named property on the
     * source record.
     *
     * <p>{@code columnName} is the property name used when accessing the source record:
     * the value of {@code @field(name:)} when present, otherwise the GraphQL field name.
     */
    record RecordField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef returnType,
        String columnName
    ) implements ChildField {}

    /**
     * A child field resolved by a developer-provided jOOQ {@code Field<?>} expression via {@code @externalField}.
     *
     * <p>{@code returnType} is the resolved outcome of looking up the return type in the classified
     * schema.
     *
     * <p>{@code joinPath} is the ordered list of join steps extracted from {@code @reference(path:)},
     * providing the lift condition that reconnects this field's result back to the parent table.
     * Each element should carry a {@code condition} method — no FK is involved in lift conditions.
     * Empty when no {@code @reference} directive is present.
     */
    record ComputedField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef returnType,
        List<JoinStep> joinPath
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
     * {@link no.sikt.graphitron.rewrite.GraphitronSchemaValidator} reports an error for every field
     * classified here.
     */
    record MultitableReferenceField(
        String parentTypeName,
        String name,
        SourceLocation location
    ) implements ChildField {}
}
