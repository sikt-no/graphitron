package no.sikt.graphitron.rewrite.model;

import graphql.language.SourceLocation;

import java.util.List;

/**
 * A field on a non-root output type. Source context (table-mapped or result-mapped) is
 * determined by the parent {@link no.sikt.graphitron.rewrite.model.GraphitronType} at generation time.
 */
public sealed interface ChildField extends GraphitronField
    permits ChildField.ColumnField, ChildField.ColumnReferenceField,
            ChildField.CompositeColumnField, ChildField.CompositeColumnReferenceField,
            ChildField.TableTargetField,
            ChildField.TableMethodField,
            ChildField.InterfaceField, ChildField.UnionField,
            ChildField.NestingField, ChildField.ConstructorField,
            ChildField.ServiceRecordField,
            ChildField.RecordField,
            ChildField.ComputedField, ChildField.PropertyField,
            ChildField.MultitableReferenceField,
            ChildField.ErrorsField {

    /**
     * A single-column output carrier on a table-backed parent. The column's value reaches the
     * field's value through {@link #compaction()}: {@link CallSiteCompaction.Direct} for plain
     * SELECT-term projection, {@link CallSiteCompaction.NodeIdEncodeKeys} for arity-1
     * {@code @nodeId} projections that wrap the column in the per-Node
     * {@code encode<TypeName>} helper.
     */
    record ColumnField(
        String parentTypeName,
        String name,
        SourceLocation location,
        String columnName,
        ColumnRef column,
        CallSiteCompaction compaction
    ) implements ChildField {}

    /**
     * A single-column output carrier on a table-backed parent reached through a {@code @reference}
     * path. The terminal column lives on the joined target table; {@link #compaction()} controls
     * how the value reaches the field — {@link CallSiteCompaction.Direct} for plain projection,
     * {@link CallSiteCompaction.NodeIdEncodeKeys} for arity-1 {@code @nodeId} references where
     * the parent table's keyColumns sit on the joined parent (rooted-at-parent single-hop) or
     * the FK source columns on the child positionally mirror the keyColumns (rooted-at-child).
     */
    record ColumnReferenceField(
        String parentTypeName,
        String name,
        SourceLocation location,
        String columnName,
        ColumnRef column,
        List<JoinStep> joinPath,
        CallSiteCompaction compaction
    ) implements ChildField {}

    /**
     * Composite-key output carrier on a table-backed parent reached through a {@code @reference}
     * path. Carries {@code columns} of arity &ge; 2 plus a single-hop or correlated-subquery
     * {@code joinPath}. {@code compaction} is narrowed to {@link CallSiteCompaction.NodeIdEncodeKeys}
     * at the type level: a composite-column reference projection is always a NodeId encode call;
     * no plain composite-column reference projection exists.
     */
    record CompositeColumnReferenceField(
        String parentTypeName,
        String name,
        SourceLocation location,
        List<ColumnRef> columns,
        List<JoinStep> joinPath,
        CallSiteCompaction.NodeIdEncodeKeys compaction
    ) implements ChildField {

        public CompositeColumnReferenceField {
            columns = List.copyOf(columns);
            if (columns.size() < 2) {
                throw new IllegalArgumentException(
                    "CompositeColumnReferenceField requires arity >= 2 (got " + columns.size() + "); arity-1 routes to ColumnReferenceField");
            }
        }
    }

    /**
     * Composite-key output carrier on a table-backed parent. Carries {@code columns} of arity
     * &ge; 2 (arity-1 routes to the single-column {@link ColumnField} sibling) and a
     * {@link CallSiteCompaction.NodeIdEncodeKeys} compaction whose
     * {@link HelperRef.Encode#paramSignature() encodeMethod.paramSignature} is positionally
     * equal to the NodeType's {@code keyColumns}. The slot is narrowed to
     * {@code NodeIdEncodeKeys} at the type level: no plain composite-column projection exists.
     */
    record CompositeColumnField(
        String parentTypeName,
        String name,
        SourceLocation location,
        List<ColumnRef> columns,
        CallSiteCompaction.NodeIdEncodeKeys compaction
    ) implements ChildField {

        public CompositeColumnField {
            columns = List.copyOf(columns);
            if (columns.size() < 2) {
                throw new IllegalArgumentException(
                    "CompositeColumnField requires arity >= 2 (got " + columns.size() + "); arity-1 routes to ColumnField");
            }
        }
    }

    /**
     * A child field that navigates to (or stays at) a table scope and generates SQL.
     *
     * <p>All eight variants carry the same three SQL-generation components in addition to the
     * core {@link ReturnTypeRef.TableBoundReturnType returnType} and join path:
     * <ul>
     *   <li>{@link #filters()} — ordered list of WHERE-clause contributions ({@link WhereFilter});
     *       may be empty. {@link ConditionFilter} entries represent field-level {@code @condition}
     *       methods. {@link GeneratedConditionFilter} entries represent Graphitron-generated
     *       argument-driven predicates.</li>
     *   <li>{@link #orderBy()} — authoritative ordering ({@link OrderBySpec}); always non-null.
     *       {@link OrderBySpec.None} when ordering is not applicable or not resolvable.</li>
     *   <li>{@link #pagination()} — Relay pagination arguments ({@link PaginationSpec});
     *       {@code null} when the field has no pagination arguments.</li>
     * </ul>
     *
     * <p>{@link NestingField} is intentionally excluded: it carries a
     * {@link ReturnTypeRef.TableBoundReturnType} but does not navigate — it inherits the parent's
     * table context unchanged.
     */
    sealed interface TableTargetField extends ChildField, SqlGeneratingField
        permits ChildField.TableField, ChildField.SplitTableField,
                ChildField.LookupTableField, ChildField.SplitLookupTableField,
                ChildField.TableInterfaceField,
                ChildField.ServiceTableField,
                ChildField.RecordTableField, ChildField.RecordLookupTableField {

        ReturnTypeRef.TableBoundReturnType returnType();
        List<JoinStep> joinPath();
        List<WhereFilter> filters();
        OrderBySpec orderBy();
        PaginationSpec pagination();
    }

    record TableField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef.TableBoundReturnType returnType,
        List<JoinStep> joinPath,
        List<WhereFilter> filters,
        OrderBySpec orderBy,
        PaginationSpec pagination
    ) implements TableTargetField {}

    record SplitTableField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef.TableBoundReturnType returnType,
        List<JoinStep> joinPath,
        List<WhereFilter> filters,
        OrderBySpec orderBy,
        PaginationSpec pagination,
        BatchKey batchKey
    ) implements TableTargetField, BatchKeyField {
        @Override
        public String rowsMethodName() {
            return "rows" + Character.toUpperCase(name().charAt(0)) + name().substring(1);
        }
    }

    record LookupTableField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef.TableBoundReturnType returnType,
        List<JoinStep> joinPath,
        List<WhereFilter> filters,
        OrderBySpec orderBy,
        PaginationSpec pagination,
        LookupMapping lookupMapping
    ) implements TableTargetField, LookupField {}

    record SplitLookupTableField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef.TableBoundReturnType returnType,
        List<JoinStep> joinPath,
        List<WhereFilter> filters,
        OrderBySpec orderBy,
        PaginationSpec pagination,
        BatchKey batchKey,
        LookupMapping lookupMapping
    ) implements TableTargetField, BatchKeyField, LookupField {
        @Override
        public String rowsMethodName() {
            return "rows" + Character.toUpperCase(name().charAt(0)) + name().substring(1);
        }
    }

    /**
     * A child field using {@code @tableMethod} — the developer provides a pre-filtered
     * {@code Table<?>}. The method handles all SQL generation.
     *
     * <p>The method signature is:
     * <pre>
     *     Table&lt;?&gt; method(Table&lt;?&gt; targetTable, arg1, arg2, ...)
     * </pre>
     * where the table parameter has {@link ParamSource.Table} as its source, and subsequent
     * parameters have {@link ParamSource.Arg} or {@link ParamSource.Context}.
     */
    record TableMethodField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef returnType,
        List<JoinStep> joinPath,
        MethodRef method
    ) implements ChildField, MethodBackedField {}

    record TableInterfaceField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef.TableBoundReturnType returnType,
        String discriminatorColumn,
        List<String> knownDiscriminatorValues,
        List<ParticipantRef> participants,
        List<JoinStep> joinPath,
        List<WhereFilter> filters,
        OrderBySpec orderBy,
        PaginationSpec pagination
    ) implements TableTargetField {}

    record InterfaceField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef.PolymorphicReturnType returnType
    ) implements ChildField {}

    record UnionField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef.PolymorphicReturnType returnType
    ) implements ChildField {}

    record NestingField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef.TableBoundReturnType returnType,
        List<ChildField> nestedFields
    ) implements ChildField {}

    record ConstructorField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef returnType
    ) implements ChildField {}

    /**
     * A child field backed by a developer-provided service method ({@code @service}), where the
     * return type is annotated with {@code @table} (source → table-mapped target).
     *
     * <p>Implements {@link TableTargetField} for structural uniformity. The service method replaces
     * direct SQL generation; {@link #filters()}, {@link #orderBy()}, and {@link #pagination()}
     * typically carry empty/None values unless additional filter conditions are present.
     *
     * <p>Parameter binding (including context arguments) is fully encoded in
     * {@link MethodRef#params()} via {@link ParamSource}.
     */
    /**
     * @param batchKey the batch key derived from the service method's {@link MethodRef.Param.Sourced}
     *     parameter at classification time, or {@code null} when the method has no such parameter.
     *     A {@code null} batch key means the field will fail validation — the validator reports the
     *     missing {@code Sources} parameter. Generation never sees a {@code null} batch key because
     *     validation is a prerequisite.
     */
    record ServiceTableField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef.TableBoundReturnType returnType,
        List<JoinStep> joinPath,
        List<WhereFilter> filters,
        OrderBySpec orderBy,
        PaginationSpec pagination,
        MethodRef method,
        BatchKey batchKey
    ) implements TableTargetField, MethodBackedField, BatchKeyField {
        @Override
        public String rowsMethodName() {
            return "load" + Character.toUpperCase(name().charAt(0)) + name().substring(1);
        }
    }

    /**
     * A child field backed by a developer-provided service method ({@code @service}), where the
     * return type is NOT table-mapped (source → record/scalar target).
     *
     * <p>Parameter binding (including context arguments) is fully encoded in
     * {@link MethodRef#params()} via {@link ParamSource}.
     */
    /**
     * @param batchKey the batch key derived from the service method's
     *     {@link MethodRef.Param.Sourced} parameter at classification time, or {@code null} when
     *     the method has no such parameter. A {@code null} batch key means the field will fail
     *     validation — the validator reports the missing {@code Sources} parameter. Generation
     *     never sees a {@code null} batch key because validation is a prerequisite.
     */
    record ServiceRecordField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef returnType,
        List<JoinStep> joinPath,
        MethodRef method,
        BatchKey batchKey
    ) implements ChildField, MethodBackedField, BatchKeyField {

        @Override
        public String rowsMethodName() {
            return "load" + Character.toUpperCase(name().charAt(0)) + name().substring(1);
        }

        /**
         * Returns the per-key Java element type this field's loader resolves to (the {@code V}
         * before any list-cardinality wrapping), derived from {@link #returnType()}. Used by
         * the Generator to type {@code DataLoader<K, V>} and by the Builder for strict-return-
         * type validation (Phase B / R32).
         *
         * <ul>
         *   <li>{@link ReturnTypeRef.ResultReturnType} with non-null {@code fqClassName}: that
         *       backing class.</li>
         *   <li>{@link ReturnTypeRef.ScalarReturnType}: the standard GraphQL scalar's Java type
         *       ({@code String}, {@code Boolean}, {@code Integer}, {@code Double},
         *       {@code String} for {@code ID}). Custom scalars and enums fall back to
         *       {@code String} until the consumer-provided scalar registry surfaces a typed
         *       Java class (Phase B's responsibility).</li>
         *   <li>All other cases (including {@link ReturnTypeRef.ResultReturnType} with no
         *       backing class): the reflected outer return type on {@link MethodRef#returnType()}.
         *       Phase B unwraps the V from the outer Map/List shape; Phase A shapes the loader
         *       around the outer type, which throws at request time.</li>
         * </ul>
         */
        public no.sikt.graphitron.javapoet.TypeName elementType() {
            if (returnType() instanceof ReturnTypeRef.ResultReturnType r && r.fqClassName() != null) {
                return no.sikt.graphitron.javapoet.ClassName.bestGuess(r.fqClassName());
            }
            if (returnType() instanceof ReturnTypeRef.ScalarReturnType s) {
                return scalarJavaType(s.returnTypeName());
            }
            return method().returnType();
        }

        private static no.sikt.graphitron.javapoet.TypeName scalarJavaType(String graphqlScalarName) {
            return switch (graphqlScalarName) {
                case "String", "ID" -> no.sikt.graphitron.javapoet.ClassName.get(String.class);
                case "Boolean"      -> no.sikt.graphitron.javapoet.ClassName.get(Boolean.class);
                case "Int"          -> no.sikt.graphitron.javapoet.ClassName.get(Integer.class);
                case "Float"        -> no.sikt.graphitron.javapoet.ClassName.get(Double.class);
                // Custom scalars (e.g. Date, BigDecimal) and enums fall back to String. Phase B's
                // consumer scalar registry will replace this; until then the loader signature is
                // shaped correctly for the four standard scalars (which cover the bulk of in-flight
                // schemas) and falls back to String for the rest.
                default             -> no.sikt.graphitron.javapoet.ClassName.get(String.class);
            };
        }
    }

    record RecordTableField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef.TableBoundReturnType returnType,
        List<JoinStep> joinPath,
        List<WhereFilter> filters,
        OrderBySpec orderBy,
        PaginationSpec pagination,
        BatchKey batchKey
    ) implements TableTargetField, BatchKeyField {
        @Override
        public String rowsMethodName() {
            return "rows" + Character.toUpperCase(name().charAt(0)) + name().substring(1);
        }
    }

    record RecordLookupTableField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef.TableBoundReturnType returnType,
        List<JoinStep> joinPath,
        List<WhereFilter> filters,
        OrderBySpec orderBy,
        PaginationSpec pagination,
        BatchKey batchKey,
        LookupMapping lookupMapping
    ) implements TableTargetField, BatchKeyField, LookupField {
        @Override
        public String rowsMethodName() {
            return "rows" + Character.toUpperCase(name().charAt(0)) + name().substring(1);
        }
    }

    /**
     * @param column the resolved parent-table column when the parent is a
     *     {@link GraphitronType.JooqTableRecordType} with a resolvable {@link TableRef} and the
     *     SQL column name maps to a real column; {@code null} otherwise (including for
     *     {@link GraphitronType.JooqRecordType}, {@link GraphitronType.JavaRecordType}, and
     *     {@link GraphitronType.PojoResultType} parents). When non-null, the generator emits a
     *     typed {@code Tables.X.COL} reference; when null, it falls back to
     *     {@code DSL.field("col_name")} or a bean/record accessor depending on the parent.
     */
    record RecordField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef returnType,
        String columnName,
        ColumnRef column
    ) implements ChildField {}

    /**
     * A child field using {@code @externalField} — the developer provides a static method
     * returning a jOOQ {@code Field<X>} that is inlined into the parent's projection at
     * generation time. The method handles the SQL-side computation; runtime wiring uses
     * a {@code ColumnFetcher} keyed on the GraphQL field name.
     *
     * <p>The method signature is:
     * <pre>
     *     Field&lt;X&gt; methodName(&lt;ParentTable&gt; table)
     * </pre>
     * where the table parameter has {@link ParamSource.Table} as its source. Captured by
     * {@link ServiceCatalog#reflectExternalField}.
     */
    record ComputedField(
        String parentTypeName,
        String name,
        SourceLocation location,
        ReturnTypeRef returnType,
        List<JoinStep> joinPath,
        MethodRef method
    ) implements ChildField, MethodBackedField {}

    /**
     * @param column the resolved parent-table column when the parent is a
     *     {@link GraphitronType.JooqTableRecordType} with a resolvable {@link TableRef} and the
     *     SQL column name maps to a real column; {@code null} otherwise. When non-null, the
     *     generator emits a typed {@code Tables.X.COL} reference; when null, it falls back to
     *     {@code DSL.field("col_name")} or a bean/record accessor depending on the parent.
     */
    record PropertyField(
        String parentTypeName,
        String name,
        SourceLocation location,
        String columnName,
        ColumnRef column
    ) implements ChildField {}

    record MultitableReferenceField(
        String parentTypeName,
        String name,
        SourceLocation location
    ) implements ChildField {}

    /**
     * The {@code errors} field on a payload type. Lift target for the payload-side of a
     * fetcher's typed-error channel: a list-shaped field whose element type is a single
     * {@code @error} type, a union of {@code @error} types, or an interface implemented
     * by {@code @error} types.
     *
     * <p>{@code errorTypes} is the flattened list of mapped {@code @error} types, in source
     * order: one entry for {@code [SomeError]}, the resolved members for {@code [SomeUnion]}
     * or {@code [SomeInterface]}. Polymorphism is a classification-time concern that does
     * not survive into the model; downstream the carrier-side
     * {@link ErrorChannel} consumes this list uniformly.
     *
     * <p>Emission is a passthrough fetcher: at request time the parent's payload object
     * already carries the list (the carrier's try/catch wrapper produced it, or the
     * service-method body did), so the fetcher reads it directly via graphql-java's default
     * {@code PropertyDataFetcher}.
     */
    record ErrorsField(
        String parentTypeName,
        String name,
        SourceLocation location,
        List<GraphitronType.ErrorType> errorTypes
    ) implements ChildField {

        public ErrorsField {
            errorTypes = List.copyOf(errorTypes);
        }
    }
}
