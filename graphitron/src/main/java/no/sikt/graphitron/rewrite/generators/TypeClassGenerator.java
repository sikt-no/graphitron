package no.sikt.graphitron.rewrite.generators;


import no.sikt.graphitron.javapoet.AnnotationSpec;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.javapoet.WildcardTypeName;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.model.BatchKeyField;
import no.sikt.graphitron.rewrite.model.CallParam;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.JoinStep;
import no.sikt.graphitron.rewrite.model.TableRef;
import no.sikt.graphitron.rewrite.model.WhereFilter;

import static no.sikt.graphitron.rewrite.generators.GeneratorUtils.*;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;

import static javax.lang.model.element.Modifier.PUBLIC;
import static javax.lang.model.element.Modifier.STATIC;

/**
 * Produces one type class per table-mapped GraphQL type in the schema.
 *
 * <p>Class names follow the GraphQL type name (e.g. {@code Film} for GraphQL type {@code Film}).
 * If two GraphQL types map to the same SQL table, each gets its own type class.
 *
 * <p>Each class contains a single {@code $fields(sel, table, env)} method that assembles the
 * SELECT list from a {@link graphql.schema.DataFetchingFieldSelectionSet}. The caller supplies
 * the table alias as a parameter — this is the prerequisite for G5 inline nested fields, which
 * need the parent alias for correlated join conditions. Execution (DSL context, query building,
 * pagination) is the responsibility of the calling {@code *Fetchers} class.
 *
 * <p>Generated files are placed in the {@code rewrite.types} sub-package of the configured
 * output package.
 */
public class TypeClassGenerator {

    // Cross-generator constants (LIST, ENV, SELECTED_FIELD) come from GeneratorUtils via static import.
    private static final ClassName FIELD             = ClassName.get("org.jooq", "Field");
    private static final ClassName SELECTION_SET     = ClassName.get("graphql.schema", "DataFetchingFieldSelectionSet");
    private static final ClassName ARRAY_LIST        = ClassName.get(ArrayList.class);
    private static final ClassName LINKED_HASH_SET   = ClassName.get(LinkedHashSet.class);

    public static List<TypeSpec> generate(GraphitronSchema schema, String outputPackage) {
        return schema.types().entrySet().stream()
            .filter(e -> e.getValue() instanceof GraphitronType.TableType
                      || e.getValue() instanceof GraphitronType.NodeType)
            .map(java.util.Map.Entry::getKey)
            .sorted()
            .map(typeName -> generateForType(schema, typeName, outputPackage))
            .toList();
    }

    private static TypeSpec generateForType(GraphitronSchema schema, String typeName, String outputPackage) {
        var type = (GraphitronType.TableBackedType) schema.type(typeName);
        var columnFields = schema.fieldsOf(typeName).stream()
            .filter(f -> f instanceof ChildField.ColumnField)
            .map(f -> (ChildField.ColumnField) f)
            .sorted(Comparator.comparing(GraphitronField::name))
            .toList();
        var compositeColumnFields = schema.fieldsOf(typeName).stream()
            .filter(f -> f instanceof ChildField.CompositeColumnField)
            .map(f -> (ChildField.CompositeColumnField) f)
            .sorted(Comparator.comparing(GraphitronField::name))
            .toList();
        var columnReferenceFields = schema.fieldsOf(typeName).stream()
            .filter(f -> f instanceof ChildField.ColumnReferenceField)
            .map(f -> (ChildField.ColumnReferenceField) f)
            .sorted(Comparator.comparing(GraphitronField::name))
            .toList();
        var tableFields = schema.fieldsOf(typeName).stream()
            .filter(f -> f instanceof ChildField.TableField)
            .map(f -> (ChildField.TableField) f)
            .sorted(Comparator.comparing(GraphitronField::name))
            .toList();
        var lookupTableFields = schema.fieldsOf(typeName).stream()
            .filter(f -> f instanceof ChildField.LookupTableField)
            .map(f -> (ChildField.LookupTableField) f)
            .sorted(Comparator.comparing(GraphitronField::name))
            .toList();
        var nestingFields = schema.fieldsOf(typeName).stream()
            .filter(f -> f instanceof ChildField.NestingField)
            .map(f -> (ChildField.NestingField) f)
            .sorted(Comparator.comparing(GraphitronField::name))
            .toList();
        var computedFields = schema.fieldsOf(typeName).stream()
            .filter(f -> f instanceof ChildField.ComputedField)
            .map(f -> (ChildField.ComputedField) f)
            .sorted(Comparator.comparing(GraphitronField::name))
            .toList();
        // Fields whose fetchers read parent-row columns the parent SELECT would not otherwise
        // project must opt those columns into $fields explicitly:
        //   - BatchKeyField fields (DataLoader-backed; they don't appear in $fields) — their
        //     SourceKey columns must land in the parent SELECT so key extraction reads non-null
        //     values off env.getSource().
        //   - TableMethodField on a table-bound parent — buildChildTableMethodFetcher correlates
        //     via parentRecord.get(DSL.name("<sourceSqlName>"), …) on the FK's source-side
        //     columns; without this injection, the read throws IllegalArgumentException when the
        //     user didn't request the FK column in their SDL selection.
        // Recurse into NestingField.nestedFields() so nested fields' required columns are also
        // projected by the outer parent's SELECT.
        var requiredProjectionColumns = collectRequiredProjectionColumns(schema.fieldsOf(typeName))
            .distinct()
            .toList();
        return buildTypeSpec(typeName, type.table(), columnFields, compositeColumnFields, columnReferenceFields, tableFields, lookupTableFields, nestingFields, computedFields, requiredProjectionColumns, outputPackage);
    }

    /**
     * @param typeName        the GraphQL type name (used as the class name)
     * @param tableRef        the resolved table reference with jOOQ field/class names
     * @param columnFields    the scalar column fields to include in {@code $fields()}, in declaration order
     * @param tableFields     the inline {@code @reference}-path table fields emitted as correlated
     *                        subqueries via {@link InlineTableFieldEmitter}
     * @param lookupTableFields the inline {@code @lookupKey} table fields emitted as correlated
     *                        subqueries with a VALUES + USING keyset via
     *                        {@link InlineLookupTableFieldEmitter}. Each field also contributes a
     *                        private input-rows helper method on the type class.
     */
    static TypeSpec buildTypeSpec(String typeName, TableRef tableRef,
            List<ChildField.ColumnField> columnFields,
            List<ChildField.CompositeColumnField> compositeColumnFields,
            List<ChildField.ColumnReferenceField> columnReferenceFields,
            List<ChildField.TableField> tableFields,
            List<ChildField.LookupTableField> lookupTableFields,
            List<ChildField.NestingField> nestingFields,
            List<ChildField.ComputedField> computedFields,
            List<ColumnRef> requiredProjectionColumns,
            String outputPackage) {
        var builder = TypeSpec.classBuilder(typeName)
            .addModifiers(Modifier.PUBLIC);
        // One decode-helper registry per type class: inline TableField / LookupTableField filter
        // sites that decode a @nodeId argument lift a per-class private static helper through it.
        // collectInto co-locates construct and drain so the lifted helpers land on this class
        // alongside $fields and the lift can never be silently dropped. The registry threads through
        // emitSelectionSwitch's NestingField recursion, so nested inline fields share it.
        CompositeDecodeHelperRegistry.collectInto(builder, outputPackage, registry ->
            builder.addMethod(build$FieldsMethod(tableRef, columnFields, compositeColumnFields, columnReferenceFields, tableFields, lookupTableFields, nestingFields, computedFields, requiredProjectionColumns, outputPackage, registry)));
        // Helpers for inline LookupTableFields are hoisted onto this outer type class — including
        // ones nested inside NestingField sub-types, which don't get their own type class (plain
        // objects share the parent's table context). The generated switch arm calls the helper
        // unqualified, so every reachable LookupTableField must have its helper here.
        var allLookupFields = new ArrayList<ChildField.LookupTableField>(lookupTableFields);
        collectNestedLookupFields(nestingFields, allLookupFields);
        for (var lf : allLookupFields) {
            var targetJooqTableClass = lf.returnType().table().tableClass();
            builder.addMethod(LookupValuesJoinEmitter.buildChildInputRowsMethod(lf, targetJooqTableClass));
        }
        return builder.build();
    }

    /**
     * Walks {@link ChildField.NestingField} sub-trees and accumulates every reachable
     * {@link ChildField.LookupTableField} into {@code sink}. Nested plain-object types share the
     * parent table-type's class, so their lookup-rows helpers must be emitted there too.
     */
    private static void collectNestedLookupFields(List<ChildField.NestingField> nestingFields,
                                                  List<ChildField.LookupTableField> sink) {
        for (var nf : nestingFields) {
            for (var child : nf.nestedFields()) {
                if (child instanceof ChildField.LookupTableField lf) {
                    sink.add(lf);
                } else if (child instanceof ChildField.NestingField deeper) {
                    collectNestedLookupFields(List.of(deeper), sink);
                }
            }
        }
    }

    /**
     * Generates a {@code $fields(sel, table, env)} method that assembles the SELECT list for one
     * level of the query from a {@link graphql.schema.DataFetchingFieldSelectionSet}.
     *
     * <p>{@code public static} — called cross-class from the {@code *Fetchers} classes.
     * The {@code $} prefix is chosen because GraphQL field names match {@code /[_A-Za-z][_0-9A-Za-z]&#42;/}
     * by spec, so {@code $fields} can never collide with a GraphQL field name.
     *
     * <p>{@code table} is the caller-supplied alias — the prerequisite for G5 inline nested fields,
     * which need the parent alias for correlated join conditions.
     *
     * <p>{@code env} is included now rather than deferred to G5. G5 is the immediate next roadmap
     * item; omitting it here would require a second signature migration one step later.
     */
    private static MethodSpec build$FieldsMethod(TableRef tableRef,
            List<ChildField.ColumnField> columnFields,
            List<ChildField.CompositeColumnField> compositeColumnFields,
            List<ChildField.ColumnReferenceField> columnReferenceFields,
            List<ChildField.TableField> tableFields,
            List<ChildField.LookupTableField> lookupTableFields,
            List<ChildField.NestingField> nestingFields,
            List<ChildField.ComputedField> computedFields,
            List<ColumnRef> requiredProjectionColumns,
            String outputPackage,
            CompositeDecodeHelperRegistry registry) {
        var names = GeneratorUtils.ResolvedTableNames.ofTable(tableRef);
        var fieldWildcard = ParameterizedTypeName.get(FIELD, WildcardTypeName.subtypeOf(Object.class));
        var listOfField = ParameterizedTypeName.get(LIST, fieldWildcard);
        var entryType = ParameterizedTypeName.get(
            ClassName.get("java.util", "Map", "Entry"),
            ClassName.get(String.class),
            ParameterizedTypeName.get(LIST, SELECTED_FIELD));

        // LinkedHashSet accumulator: dedupes by jOOQ Field identity while preserving insertion
        // order. Two switch arms emitting the same raw `table.X` collapse to one projection term
        // (jOOQ caches TableField references per aliased Table instance — same Java object).
        // Aliased subquery / DSL.field(...).as(name) emissions never collide because each .as(...)
        // produces a fresh Field; two arms with distinct SDL names stay distinct. The method still
        // returns List<Field<?>> (callers consume via Collection-accepting jOOQ overloads); the
        // final return wraps to ArrayList to preserve the signature.
        var builder = MethodSpec.methodBuilder("$fields")
            .addModifiers(PUBLIC, STATIC)
            .returns(listOfField)
            .addParameter(SELECTION_SET, "sel")
            .addParameter(names.jooqTableClass(), "table")
            .addParameter(ENV, "env")
            .addStatement("$T<$T> fields = new $T<>()", LINKED_HASH_SET, fieldWildcard, LINKED_HASH_SET);

        var flat = new ArrayList<ChildField>();
        flat.addAll(columnFields);
        flat.addAll(compositeColumnFields);
        flat.addAll(columnReferenceFields);
        flat.addAll(tableFields);
        flat.addAll(lookupTableFields);
        flat.addAll(nestingFields);
        flat.addAll(computedFields);
        // R424: stamp @SuppressWarnings("unchecked") on $fields — the narrowest enclosing member —
        // when any inline field's filter param emits an unchecked cast under the FromSelectedField
        // argument source (a list-typed Direct / JooqConvert / non-JooqConvert-leaf NestedInputField).
        // The predicate is source-aware (CallParam.emitsUncheckedCastFromSelectedField): the casts
        // exist only here, so the Env hosts (QueryConditionsGenerator / MultiTablePolymorphicEmitter)
        // keep their warning-free, byte-identical output. Walks nested inline fields too, since
        // NestingField sub-trees emit their inline arms into this same $fields method.
        if (inlineFiltersNeedUncheckedSuppression(flat)) {
            builder.addAnnotation(AnnotationSpec.builder(SuppressWarnings.class)
                .addMember("value", "$S", "unchecked")
                .build());
        }

        emitSelectionSwitch(builder, 0, flat, "table", entryType, outputPackage, registry);

        // Required-projection set: columns the parent SELECT must include regardless of the
        // user's SDL selection — SourceKey columns for DataLoader-backed BatchKeyField children,
        // FK source-side columns for child @tableMethod fields (see
        // collectRequiredProjectionColumns for the full taxonomy). The LinkedHashSet accumulator dedupes on add by jOOQ Field
        // identity, so a plain fields.add(...) is safe; no explicit contains() guard is needed.
        for (ColumnRef col : requiredProjectionColumns) {
            builder.addStatement("fields.add(table.$L)", col.javaName());
        }

        builder.addStatement("return new $T<>(fields)", ARRAY_LIST);
        return builder.build();
    }

    /**
     * Emits a {@code for}/{@code switch} block projecting the supplied fields from
     * {@code selN.getFieldsGroupedByResultKey()} (where N is the recursion depth). At depth 0 the
     * loop variables are {@code entry}/{@code sf} — matching the names that the inline table-field
     * and lookup-table-field emitters embed in their generated code. Nested depths (1, 2, …) append
     * the depth number to avoid shadowing outer scopes — plain {@code entry}/{@code sf} would
     * violate JLS §14.4.2.
     *
     * <p>A {@code NestingField} arm recurses with {@code depth + 1}, reading from the current
     * depth's {@code sf.getSelectionSet()}. The nested type shares the parent's table context, so
     * {@code tableArg} is threaded through unchanged.
     *
     * <p>R424: the per-depth {@code sfN} local is the {@code SelectedField} the inline table /
     * lookup-field arms read their own runtime <em>arguments</em> from (via
     * {@code ArgumentValueSource.FromSelectedField}). The {@code $fields} method's {@code env}
     * parameter stays the ancestor fetcher's environment at every depth and is used only for
     * request-scoped context reads; it is deliberately <em>not</em> the source of field arguments.
     */
    private static void emitSelectionSwitch(MethodSpec.Builder builder, int depth,
                                            List<ChildField> fields, String tableArg,
                                            ParameterizedTypeName entryType,
                                            String outputPackage,
                                            CompositeDecodeHelperRegistry registry) {
        String parentSel = (depth == 0) ? "sel" : (sfName(depth - 1) + ".getSelectionSet()");
        String entry = entryName(depth);
        String sf = sfName(depth);

        builder.addCode("for ($T $L : $L.getFieldsGroupedByResultKey().entrySet()) {\n", entryType, entry, parentSel);
        builder.addCode("    $T $L = $L.getValue().get(0);\n", SELECTED_FIELD, sf, entry);
        builder.addCode("    switch ($L.getName()) {\n", sf);
        for (var f : fields) {
            switch (f) {
                case ChildField.ColumnField cf ->
                    // Compaction (Direct vs NodeIdEncodeKeys) does not affect projection — the
                    // SELECT term is the same column in both cases. The wrapping happens at the
                    // fetcher value, not in the SELECT clause; see FetcherEmitter.
                    builder.addCode("        case $S -> fields.add($L.$L);\n",
                        cf.name(), tableArg, cf.column().javaName());
                case ChildField.CompositeColumnField ccf -> {
                    builder.addCode("        case $S -> {\n", ccf.name());
                    for (var col : ccf.columns()) {
                        builder.addCode("            fields.add($L.$L);\n", tableArg, col.javaName());
                    }
                    builder.addCode("        }\n");
                }
                case ChildField.ColumnReferenceField crf -> {
                    builder.addCode("        case $S -> {\n", crf.name());
                    builder.addCode("$L", InlineColumnReferenceFieldEmitter.buildSwitchArmBody(crf, tableArg, sf, outputPackage));
                    builder.addCode("        }\n");
                }
                case ChildField.TableField tf -> {
                    builder.addCode("        case $S -> {\n", tf.name());
                    builder.addCode("$L", InlineTableFieldEmitter.buildSwitchArmBody(tf, tableArg, sf, outputPackage, registry));
                    builder.addCode("        }\n");
                }
                case ChildField.LookupTableField lf -> {
                    builder.addCode("        case $S -> {\n", lf.name());
                    builder.addCode("$L", InlineLookupTableFieldEmitter.buildSwitchArmBody(lf, tableArg, sf, outputPackage, registry));
                    builder.addCode("        }\n");
                }
                case ChildField.NestingField nf -> {
                    builder.addCode("        case $S -> {\n", nf.name());
                    emitSelectionSwitch(builder, depth + 1, nf.nestedFields(), tableArg, entryType, outputPackage, registry);
                    builder.addCode("        }\n");
                }
                case ChildField.ComputedField cf -> {
                    var refClass = ClassName.bestGuess(cf.method().className());
                    builder.addCode("        case $S -> fields.add($T.$L($L).as($S));\n",
                        cf.name(), refClass, cf.method().methodName(), tableArg, cf.name());
                }
                default -> {
                    // Unexpected variants in a projection switch are skipped — validator rejects them.
                }
            }
        }
        builder.addCode("        default -> { } // unhandled fields\n");
        builder.addCode("    }\n");
        builder.addCode("}\n");
    }


    private static String sfName(int depth) { return depth == 0 ? "sf" : "sf" + depth; }
    private static String entryName(int depth) { return depth == 0 ? "entry" : "entry" + depth; }

    /**
     * R424: true when any inline {@link ChildField.TableField} / {@link ChildField.LookupTableField}
     * filter among {@code fields} (recursing into {@link ChildField.NestingField} sub-trees, which
     * emit their inline arms into the same {@code $fields} method) carries a call param that emits an
     * unchecked cast under the {@code FromSelectedField} argument source. The model owns the
     * per-source cast fact ({@link CallParam#emitsUncheckedCastFromSelectedField()}), so this host and
     * the {@code Env} hosts cannot drift.
     */
    private static boolean inlineFiltersNeedUncheckedSuppression(List<? extends ChildField> fields) {
        for (var f : fields) {
            List<WhereFilter> filters = switch (f) {
                case ChildField.TableField tf -> tf.filters();
                case ChildField.LookupTableField lf -> lf.filters();
                default -> List.of();
            };
            boolean hit = filters.stream()
                .flatMap(wf -> wf.callParams().stream())
                .anyMatch(CallParam::emitsUncheckedCastFromSelectedField);
            if (hit) return true;
            if (f instanceof ChildField.NestingField nf
                    && inlineFiltersNeedUncheckedSuppression(nf.nestedFields())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Walks the children of a type and surfaces every column the parent SELECT must project
     * regardless of the user's SDL selection. Two sources today:
     *
     * <ul>
     *   <li>Table-parent {@link BatchKeyField} implementers' {@code SourceKey} columns — their
     *       DataLoader fetchers extract the parent-row key off {@code env.getSource()} after the
     *       parent {@code $fields()} SELECT runs (via {@code GeneratorUtils.buildKeyExtraction}),
     *       so every {@code sourceKey().columns()} column must be in that SELECT.</li>
     *   <li>{@link ChildField.TableMethodField} on a table-bound parent — the fetcher built by
     *       {@code TypeFetcherGenerator.buildChildTableMethodFetcher} correlates the developer's
     *       returned table against the parent via {@code parentRecord.get(DSL.name("<src>"), …)}
     *       on the resolved FK's source-side columns. Only the single-hop {@link JoinStep.FkJoin}
     *       shape is projected: multi-hop and {@code ConditionJoin} paths surface a runtime
     *       {@code UnsupportedOperationException} in the emitter, so projecting their first hop
     *       would synthesise dead columns.</li>
     * </ul>
     *
     * <p>Recurses into {@link ChildField.NestingField} so nested fields whose fetchers need
     * parent-row columns get those columns into the outer table-class's {@code $fields}.
     */
    private static java.util.stream.Stream<ColumnRef> collectRequiredProjectionColumns(List<? extends GraphitronField> fields) {
        return fields.stream().flatMap(f -> {
            // Soundness invariant of the blanket BatchKeyField arm below: SourceKey.columns() is
            // parent-side or target-side depending on shape, and this walk runs only under
            // generate()'s TableType/NodeType filter, so every BatchKeyField it sees was
            // classified on a table-backed parent and carries parent-side columns read by
            // buildKeyExtraction. The record-parent implementers key off a Java accessor via
            // buildRecordParentKeyExtraction instead and may carry target-aligned columns; if one
            // ever leaked into this walk, the blanket arm would silently project wrong columns.
            // Fail at generation time rather than at runtime with a null DataLoader key.
            if (f instanceof ChildField.RecordTableField
                    || f instanceof ChildField.RecordLookupTableField
                    || f instanceof ChildField.RecordTableMethodField)
                throw new IllegalStateException(
                    "Record-parent field '" + f.name() + "' (" + f.getClass().getSimpleName()
                        + ") reached a table-parent $fields projection walk; its SourceKey columns"
                        + " are not parent-row columns and must not be force-projected");
            // A null sourceKey means the service method takes no Sources param: the field is a
            // plain per-parent service delegation, not DataLoader-backed, and its fetcher reads
            // no key columns off the parent row — nothing to force-project.
            if (f instanceof BatchKeyField bk)
                return bk.sourceKey() == null
                    ? java.util.stream.Stream.<ColumnRef>empty()
                    : bk.sourceKey().columns().stream();
            if (f instanceof ChildField.TableMethodField tmf) {
                var path = tmf.joinPath();
                if (path.size() == 1 && path.get(0) instanceof JoinStep.FkJoin fk) {
                    return fk.sourceSideColumns().stream();
                }
                return java.util.stream.Stream.empty();
            }
            if (f instanceof ChildField.NestingField nf)
                return collectRequiredProjectionColumns(nf.nestedFields());
            return java.util.stream.Stream.empty();
        });
    }

}
