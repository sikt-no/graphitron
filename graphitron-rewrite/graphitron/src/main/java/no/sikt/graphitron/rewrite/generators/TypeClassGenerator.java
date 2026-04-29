package no.sikt.graphitron.rewrite.generators;


import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.javapoet.WildcardTypeName;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.model.BatchKey;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.TableRef;

import static no.sikt.graphitron.rewrite.generators.GeneratorUtils.*;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.Comparator;
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
    private static final ClassName FIELD          = ClassName.get("org.jooq", "Field");
    private static final ClassName SELECTION_SET  = ClassName.get("graphql.schema", "DataFetchingFieldSelectionSet");
    private static final ClassName ARRAY_LIST     = ClassName.get(ArrayList.class);

    public static List<TypeSpec> generate(GraphitronSchema schema, String outputPackage, String jooqPackage) {
        return schema.types().entrySet().stream()
            .filter(e -> e.getValue() instanceof GraphitronType.TableType
                      || e.getValue() instanceof GraphitronType.NodeType)
            .map(java.util.Map.Entry::getKey)
            .sorted()
            .map(typeName -> generateForType(schema, typeName, outputPackage, jooqPackage))
            .toList();
    }

    private static TypeSpec generateForType(GraphitronSchema schema, String typeName, String outputPackage, String jooqPackage) {
        var type = (GraphitronType.TableBackedType) schema.type(typeName);
        var columnFields = schema.fieldsOf(typeName).stream()
            .filter(f -> f instanceof ChildField.ColumnField)
            .map(f -> (ChildField.ColumnField) f)
            .sorted(Comparator.comparing(GraphitronField::name))
            .toList();
        var nodeIdFields = schema.fieldsOf(typeName).stream()
            .filter(f -> f instanceof ChildField.NodeIdField)
            .map(f -> (ChildField.NodeIdField) f)
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
        // Split* fields don't appear in $fields (they're handled by DataLoader-backed fetchers),
        // but their BatchKey columns must land in the parent SELECT so key extraction reads
        // non-null values off env.getSource(). Recurse into NestingField.nestedFields() so nested
        // Split fields' BatchKey columns are also projected by the outer parent's SELECT.
        var requiredProjectionColumns = collectBatchKeyColumns(schema.fieldsOf(typeName))
            .distinct()
            .toList();
        return buildTypeSpec(typeName, type.table(), columnFields, nodeIdFields, tableFields, lookupTableFields, nestingFields, computedFields, requiredProjectionColumns, outputPackage, jooqPackage);
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
            List<ChildField.NodeIdField> nodeIdFields,
            List<ChildField.TableField> tableFields,
            List<ChildField.LookupTableField> lookupTableFields,
            List<ChildField.NestingField> nestingFields,
            List<ChildField.ComputedField> computedFields,
            List<ColumnRef> requiredProjectionColumns,
            String outputPackage, String jooqPackage) {
        var builder = TypeSpec.classBuilder(typeName)
            .addModifiers(Modifier.PUBLIC)
            .addMethod(build$FieldsMethod(tableRef, columnFields, nodeIdFields, tableFields, lookupTableFields, nestingFields, computedFields, requiredProjectionColumns, outputPackage, jooqPackage));
        // Helpers for inline LookupTableFields are hoisted onto this outer type class — including
        // ones nested inside NestingField sub-types, which don't get their own type class (plain
        // objects share the parent's table context). The generated switch arm calls the helper
        // unqualified, so every reachable LookupTableField must have its helper here.
        var allLookupFields = new ArrayList<ChildField.LookupTableField>(lookupTableFields);
        collectNestedLookupFields(nestingFields, allLookupFields);
        for (var lf : allLookupFields) {
            // NodeIdMapping uses hasIds/hasId inline — no VALUES+JOIN input-rows helper needed.
            if (lf.lookupMapping() instanceof no.sikt.graphitron.rewrite.model.LookupMapping.ColumnMapping) {
                var targetJooqTableClass = ClassName.get(
                    jooqPackage + ".tables",
                    lf.returnType().table().javaClassName());
                builder.addMethod(LookupValuesJoinEmitter.buildChildInputRowsMethod(lf, targetJooqTableClass));
            }
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
            List<ChildField.NodeIdField> nodeIdFields,
            List<ChildField.TableField> tableFields,
            List<ChildField.LookupTableField> lookupTableFields,
            List<ChildField.NestingField> nestingFields,
            List<ChildField.ComputedField> computedFields,
            List<ColumnRef> requiredProjectionColumns,
            String outputPackage, String jooqPackage) {
        var names = GeneratorUtils.ResolvedTableNames.ofTable(tableRef, jooqPackage);
        var fieldWildcard = ParameterizedTypeName.get(FIELD, WildcardTypeName.subtypeOf(Object.class));
        var listOfField = ParameterizedTypeName.get(LIST, fieldWildcard);
        var entryType = ParameterizedTypeName.get(
            ClassName.get("java.util", "Map", "Entry"),
            ClassName.get(String.class),
            ParameterizedTypeName.get(LIST, SELECTED_FIELD));

        var builder = MethodSpec.methodBuilder("$fields")
            .addModifiers(PUBLIC, STATIC)
            .returns(listOfField)
            .addParameter(SELECTION_SET, "sel")
            .addParameter(names.jooqTableClass(), "table")
            .addParameter(ENV, "env")
            .addStatement("$T<$T> fields = new $T<>()", ARRAY_LIST, fieldWildcard, ARRAY_LIST);

        var flat = new ArrayList<ChildField>();
        flat.addAll(columnFields);
        flat.addAll(nodeIdFields);
        flat.addAll(tableFields);
        flat.addAll(lookupTableFields);
        flat.addAll(nestingFields);
        flat.addAll(computedFields);
        emitSelectionSwitch(builder, 0, flat, "table", entryType, outputPackage, jooqPackage);

        // Required-projection set: BatchKey columns of every DataLoader-backed Split* child on
        // this type must land in the SELECT regardless of selection, so parent key extraction
        // in the fetcher reads non-null values. Dedup at runtime against whatever the selection
        // switch appended (jOOQ Field identity on the aliased table).
        for (ColumnRef col : requiredProjectionColumns) {
            builder.addStatement("if (!fields.contains(table.$L)) fields.add(table.$L)",
                col.javaName(), col.javaName());
        }

        builder.addStatement("return fields");
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
     */
    private static void emitSelectionSwitch(MethodSpec.Builder builder, int depth,
                                            List<ChildField> fields, String tableArg,
                                            ParameterizedTypeName entryType,
                                            String outputPackage, String jooqPackage) {
        String parentSel = (depth == 0) ? "sel" : (sfName(depth - 1) + ".getSelectionSet()");
        String entry = entryName(depth);
        String sf = sfName(depth);

        builder.addCode("for ($T $L : $L.getFieldsGroupedByResultKey().entrySet()) {\n", entryType, entry, parentSel);
        builder.addCode("    $T $L = $L.getValue().get(0);\n", SELECTED_FIELD, sf, entry);
        builder.addCode("    switch ($L.getName()) {\n", sf);
        for (var f : fields) {
            switch (f) {
                case ChildField.ColumnField cf ->
                    builder.addCode("        case $S -> fields.add($L.$L);\n",
                        cf.name(), tableArg, cf.column().javaName());
                case ChildField.NodeIdField nif -> {
                    builder.addCode("        case $S -> {\n", nif.name());
                    for (var col : nif.nodeKeyColumns()) {
                        builder.addCode("            fields.add($L.$L);\n", tableArg, col.javaName());
                    }
                    builder.addCode("        }\n");
                }
                case ChildField.NodeIdReferenceField nrf -> {
                    // FK-mirror collapse: when the joinPath is a single FkJoin entered from the
                    // parent table (parent holds the FK), the FK source columns on the parent
                    // mirror the target's nodeKeyColumns by FK constraint — no JOIN is needed,
                    // we project the parent's FK columns directly. Other shapes (composite-key
                    // FK that doesn't mirror, multi-hop, condition-join) emit nothing here and
                    // are stubbed at the fetcher arm.
                    var fkMirror = fkMirrorSourceColumns(nrf);
                    if (fkMirror != null) {
                        builder.addCode("        case $S -> {\n", nrf.name());
                        for (var col : fkMirror) {
                            builder.addCode("            fields.add($L.$L);\n", tableArg, col.javaName());
                        }
                        builder.addCode("        }\n");
                    }
                }
                case ChildField.TableField tf -> {
                    builder.addCode("        case $S -> {\n", tf.name());
                    builder.addCode("$L", InlineTableFieldEmitter.buildSwitchArmBody(tf, tableArg, sf, outputPackage, jooqPackage));
                    builder.addCode("        }\n");
                }
                case ChildField.LookupTableField lf -> {
                    builder.addCode("        case $S -> {\n", lf.name());
                    builder.addCode("$L", InlineLookupTableFieldEmitter.buildSwitchArmBody(lf, tableArg, sf, outputPackage, jooqPackage));
                    builder.addCode("        }\n");
                }
                case ChildField.NestingField nf -> {
                    builder.addCode("        case $S -> {\n", nf.name());
                    emitSelectionSwitch(builder, depth + 1, nf.nestedFields(), tableArg, entryType, outputPackage, jooqPackage);
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

    /**
     * Returns the FK source columns on the parent table when the {@code NodeIdReferenceField}'s
     * join path collapses to a single FK hop entered from the parent (parent-holds-FK pattern)
     * <em>and</em> the FK's target columns positionally match the target's {@code nodeKeyColumns}.
     * In that case, the FK source columns on the parent are equal-by-constraint to the target's
     * nodeId key columns, so we can project them directly off the parent and skip the JOIN.
     *
     * <p>Returns {@code null} when the field requires the full JOIN form (composite key with a
     * non-mirroring FK, multi-hop path, condition join). Those cases are stubbed at the fetcher
     * arm — see {@link no.sikt.graphitron.rewrite.generators.FetcherEmitter}.
     */
    static java.util.List<ColumnRef> fkMirrorSourceColumns(ChildField.NodeIdReferenceField nrf) {
        if (nrf.joinPath().size() != 1) return null;
        if (!(nrf.joinPath().get(0) instanceof no.sikt.graphitron.rewrite.model.JoinStep.FkJoin fk)) return null;
        if (!fk.originTable().tableName().equalsIgnoreCase(nrf.parentTable().tableName())) return null;
        if (fk.targetColumns().size() != nrf.nodeKeyColumns().size()) return null;
        for (int i = 0; i < fk.targetColumns().size(); i++) {
            if (!fk.targetColumns().get(i).sqlName().equalsIgnoreCase(nrf.nodeKeyColumns().get(i).sqlName())) {
                return null;
            }
        }
        return fk.sourceColumns();
    }

    private static String sfName(int depth) { return depth == 0 ? "sf" : "sf" + depth; }
    private static String entryName(int depth) { return depth == 0 ? "entry" : "entry" + depth; }

    private static java.util.stream.Stream<ColumnRef> collectBatchKeyColumns(List<? extends GraphitronField> fields) {
        return fields.stream().flatMap(f -> {
            if (f instanceof ChildField.SplitTableField stf && stf.batchKey() instanceof BatchKey.RowKeyed rk)
                return rk.keyColumns().stream();
            if (f instanceof ChildField.SplitLookupTableField slf && slf.batchKey() instanceof BatchKey.RowKeyed rk)
                return rk.keyColumns().stream();
            if (f instanceof ChildField.NestingField nf)
                return collectBatchKeyColumns(nf.nestedFields());
            return java.util.stream.Stream.empty();
        });
    }

}
