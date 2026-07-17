package no.sikt.graphitron.rewrite.generators;


import no.sikt.graphitron.javapoet.AnnotationSpec;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.javapoet.WildcardTypeName;
import no.sikt.graphitron.rewrite.generators.util.SelectionOccurrencesClassGenerator;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.model.BatchKeyField;
import no.sikt.graphitron.rewrite.model.CallParam;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.JoinStep;
import no.sikt.graphitron.rewrite.model.ParentRowDemand;
import no.sikt.graphitron.rewrite.model.SourceKey;
import no.sikt.graphitron.rewrite.model.SourceShape;
import no.sikt.graphitron.rewrite.model.TableRef;
import no.sikt.graphitron.rewrite.model.WhereFilter;

import static no.sikt.graphitron.rewrite.generators.GeneratorUtils.*;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;

import static javax.lang.model.element.Modifier.PRIVATE;
import static javax.lang.model.element.Modifier.PUBLIC;
import static javax.lang.model.element.Modifier.STATIC;

/**
 * Produces one type class per table-mapped GraphQL type in the schema.
 *
 * <p>Class names follow the GraphQL type name (e.g. {@code Film} for GraphQL type {@code Film}).
 * If two GraphQL types map to the same SQL table, each gets its own type class.
 *
 * <p>Each class contains a {@code $fields} method pair that assembles the SELECT list for one
 * level of the query: the fetcher-facing entry takes a
 * {@link graphql.schema.DataFetchingFieldSelectionSet}, and an occurrence-list overload takes the
 * {@code List<SelectedField>} bucket a shared result key groups to (several occurrences when
 * sibling selection paths collapse onto one key, e.g. {@code edges.node} vs {@code nodes} in a
 * Relay connection). Both delegate to one private switch loop over the result-key-grouped map;
 * the occurrence overload first unions all occurrences' sub-selections via the generated
 * {@code SelectionOccurrences} scaffold
 * ({@link no.sikt.graphitron.rewrite.generators.util.SelectionOccurrencesClassGenerator}). The
 * caller supplies the table alias as a parameter — this is the prerequisite for G5 inline nested
 * fields, which need the parent alias for correlated join conditions. Execution (DSL context,
 * query building, pagination) is the responsibility of the calling {@code *Fetchers} class.
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
    private static final ClassName MAP               = ClassName.get("java.util", "Map");

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
        //     values off env.getSource(). When the key wrap is SourceKey.Wrap.TableRecord, the
        //     extraction (into(Tables.X)) hands the service body a typed record whose documented
        //     contract is "every column on the parent table", so the requirement widens from the
        // key columns to the full parent row.
        //   - TableMethodField on a table-bound parent — buildChildTableMethodFetcher correlates
        //     via parentRecord.get(DSL.name("<sourceSqlName>"), …) on the FK's source-side
        //     columns; without this injection, the read throws IllegalArgumentException when the
        //     user didn't request the FK column in their SDL selection.
        // Recurse into NestingField.nestedFields() so nested fields' required columns are also
        // projected by the outer parent's SELECT.
        var requiredProjection = collectRequiredProjection(schema.fieldsOf(typeName));
        // Cross-check the walk above against an independent demand enumeration (the
        // parent-projection containment invariant); the omission it exists to catch is a child's
        // DataLoader key column being absent from the parent row and silently null. Keyed on the
        // BatchKeyField capability + sourceShape(), not leaf identity.
        ParentProjectionContainmentCheck.check(schema, typeName, requiredProjection);
        return buildTypeSpec(typeName, type.table(), columnFields, compositeColumnFields, columnReferenceFields, tableFields, lookupTableFields, nestingFields, computedFields, requiredProjection, outputPackage);
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
            RequiredProjection requiredProjection,
            String outputPackage) {
        var builder = TypeSpec.classBuilder(typeName)
            .addModifiers(Modifier.PUBLIC);
        builder.addMethod(buildSelectionSetEntryMethod(tableRef));
        builder.addMethod(buildOccurrencesEntryMethod(tableRef, outputPackage));
        // One decode-helper registry per type class: inline TableField / LookupTableField filter
        // sites that decode a @nodeId argument lift a per-class private static helper through it.
        // collectInto co-locates construct and drain so the lifted helpers land on this class
        // alongside $fields and the lift can never be silently dropped. The registry threads through
        // emitSelectionSwitch's NestingField recursion, so nested inline fields share it.
        CompositeDecodeHelperRegistry.collectInto(builder, outputPackage, registry ->
            builder.addMethod(build$FieldsGroupedMethod(tableRef, columnFields, compositeColumnFields, columnReferenceFields, tableFields, lookupTableFields, nestingFields, computedFields, requiredProjection, outputPackage, registry)));
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
     * Generates the fetcher-facing {@code $fields(sel, table, env)} entry. Delegates to the
     * private grouped switch loop ({@link #build$FieldsGroupedMethod}) via
     * {@code sel.getFieldsGroupedByResultKey()}; the signature is the stable cross-class surface
     * every {@code *Fetchers} call site (and the polymorphic {@code restrictTo} view) targets.
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
    private static MethodSpec buildSelectionSetEntryMethod(TableRef tableRef) {
        var names = GeneratorUtils.ResolvedTableNames.ofTable(tableRef);
        return MethodSpec.methodBuilder("$fields")
            .addModifiers(PUBLIC, STATIC)
            .returns(listOfFieldWildcard())
            .addParameter(SELECTION_SET, "sel")
            .addParameter(names.jooqTableClass(), "table")
            .addParameter(ENV, "env")
            .addStatement("return $$fieldsGrouped(sel.getFieldsGroupedByResultKey(), table, env)")
            .build();
    }

    /**
     * Generates the occurrence-list {@code $fields(occurrences, table, env)} overload: the entry
     * the inline {@code TableField} / {@code LookupTableField} switch arms descend through with the
     * full result-key bucket ({@code entry.getValue()}), so a nested projection operates on the
     * <em>union</em> of every occurrence's sub-selection rather than only the first occurrence's.
     * The union happens on the selection side (one merged map, one arm emission, one SELECT term
     * per result key); re-emitting an arm per occurrence would instead mint duplicate
     * {@code DSL.multiset(...).as(...)} SQL aliases, because {@code .as(...)} produces a fresh jOOQ
     * {@code Field} on every call and the accumulator dedupes raw column adds only.
     */
    private static MethodSpec buildOccurrencesEntryMethod(TableRef tableRef, String outputPackage) {
        var names = GeneratorUtils.ResolvedTableNames.ofTable(tableRef);
        return MethodSpec.methodBuilder("$fields")
            .addModifiers(PUBLIC, STATIC)
            .returns(listOfFieldWildcard())
            .addParameter(ParameterizedTypeName.get(LIST, SELECTED_FIELD), "occurrences")
            .addParameter(names.jooqTableClass(), "table")
            .addParameter(ENV, "env")
            .addStatement("return $$fieldsGrouped($T.mergeByResultKey(occurrences), table, env)",
                selectionOccurrencesClass(outputPackage))
            .build();
    }

    /**
     * Generates the private {@code $fieldsGrouped(grouped, table, env)} switch loop both public
     * {@code $fields} entries delegate to: it assembles the SELECT list for one level of the query
     * from a result-key-grouped selection map (the shape
     * {@code DataFetchingFieldSelectionSet.getFieldsGroupedByResultKey()} produces and
     * {@code SelectionOccurrences.mergeByResultKey} reproduces for occurrence lists).
     */
    private static MethodSpec build$FieldsGroupedMethod(TableRef tableRef,
            List<ChildField.ColumnField> columnFields,
            List<ChildField.CompositeColumnField> compositeColumnFields,
            List<ChildField.ColumnReferenceField> columnReferenceFields,
            List<ChildField.TableField> tableFields,
            List<ChildField.LookupTableField> lookupTableFields,
            List<ChildField.NestingField> nestingFields,
            List<ChildField.ComputedField> computedFields,
            RequiredProjection requiredProjection,
            String outputPackage,
            CompositeDecodeHelperRegistry registry) {
        var names = GeneratorUtils.ResolvedTableNames.ofTable(tableRef);
        var fieldWildcard = ParameterizedTypeName.get(FIELD, WildcardTypeName.subtypeOf(Object.class));
        var listOfField = ParameterizedTypeName.get(LIST, fieldWildcard);
        var groupedType = ParameterizedTypeName.get(MAP,
            ClassName.get(String.class),
            ParameterizedTypeName.get(LIST, SELECTED_FIELD));
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
        var builder = MethodSpec.methodBuilder("$fieldsGrouped")
            .addModifiers(PRIVATE, STATIC)
            .returns(listOfField)
            .addParameter(groupedType, "grouped")
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
        // Stamp @SuppressWarnings("unchecked") on $fieldsGrouped — the narrowest enclosing member —
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

        // Required-projection set: what the parent SELECT must include regardless of the user's
        // SDL selection — SourceKey columns for DataLoader-backed BatchKeyField children, FK
        // source-side columns for child @tableMethod fields (both base-named), and/or the whole
        // parent row under reserved __src_<col>__ aliases when a child's key wrap is
        // SourceKey.Wrap.TableRecord (see collectRequiredProjection for the full taxonomy).
        // The two axes are independent and both emitted: the reserved full row no longer
        // supplies base-named columns, so a base-named Wrap.Row/Wrap.Record/TableMethodField read
        // still needs its columns projected under their base names even when the full row is also
        // present. The LinkedHashSet accumulator dedupes on add by jOOQ Field identity, so plain
        // adds are safe; base and reserved-aliased projections of the same column are distinct
        // Field instances (.as(...) mints a fresh Field) and coexist by design.
        //
        // The reserved-alias append drives off tableRef.allColumns() at generation time — the same
        // list GeneratorUtils.buildKeyExtraction reads the values back by — so the projected alias
        // names and the extraction's lookup names are single-homed and cannot drift. `table` is the
        // caller's (possibly aliased) instance; table.<COL>.as(reserved) keeps values/converters
        // and only moves the projected name out of the client-reachable base namespace.
        if (requiredProjection.reservedFullRow()) {
            for (ColumnRef col : tableRef.allColumns()) {
                builder.addStatement("fields.add(table.$L.as($S))",
                    col.javaName(), reservedSourceAlias(col.sqlName()));
            }
        }
        for (ColumnRef col : requiredProjection.baseColumns()) {
            builder.addStatement("fields.add(table.$L)", col.javaName());
        }

        builder.addStatement("return new $T<>(fields)", ARRAY_LIST);
        return builder.build();
    }

    /**
     * Emits a {@code for}/{@code switch} block projecting the supplied fields from the
     * result-key-grouped map in scope: the {@code grouped} parameter at depth 0, and the
     * {@code SelectionOccurrences.mergeByResultKey(entryN.getValue())} union of the previous
     * depth's occurrence bucket at nested depths. At depth 0 the loop variables are
     * {@code entry}/{@code sf} — matching the names that the inline table-field and
     * lookup-table-field emitters embed in their generated code. Nested depths (1, 2, …) append
     * the depth number to avoid shadowing outer scopes — plain {@code entry}/{@code sf} would
     * violate JLS §14.4.2.
     *
     * <p>A {@code NestingField} arm recurses with {@code depth + 1}, reading from the merged map
     * of the current depth's bucket. The nested type shares the parent's table context, so
     * {@code tableArg} is threaded through unchanged.
     *
     * <p>The per-depth {@code sfN} local is the <em>canonical</em> occurrence of the bucket
     * ({@code SelectionOccurrences.canonical(...)}, which fail-louds when occurrences under one
     * result key name different underlying fields — a shape the single-name {@code switch}
     * dispatch cannot represent for any arm). It is the {@code SelectedField} the inline table /
     * lookup-field arms read their own runtime <em>arguments</em> from (via
     * {@code ArgumentValueSource.FromSelectedField}); those arms additionally guard that all
     * occurrences agree on the arguments before serving the canonical one's. The enclosing
     * method's {@code env} parameter stays the ancestor fetcher's environment at every depth and
     * is used only for request-scoped context reads; it is deliberately <em>not</em> the source of
     * field arguments.
     */
    private static void emitSelectionSwitch(MethodSpec.Builder builder, int depth,
                                            List<ChildField> fields, String tableArg,
                                            ParameterizedTypeName entryType,
                                            String outputPackage,
                                            CompositeDecodeHelperRegistry registry) {
        String entry = entryName(depth);
        String sf = sfName(depth);
        var selectionOccurrences = selectionOccurrencesClass(outputPackage);
        var parentGrouped = (depth == 0)
            ? CodeBlock.of("grouped")
            : CodeBlock.of("$T.mergeByResultKey($L.getValue())",
                selectionOccurrences, entryName(depth - 1));

        builder.addCode("for ($T $L : $L.entrySet()) {\n", entryType, entry, parentGrouped);
        builder.addCode("    $T $L = $T.canonical($L.getKey(), $L.getValue());\n",
            SELECTED_FIELD, sf, selectionOccurrences, entry, entry);
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
                    builder.addCode("$L", InlineTableFieldEmitter.buildSwitchArmBody(tf, tableArg, sf, entry, outputPackage, registry));
                    builder.addCode("        }\n");
                }
                case ChildField.LookupTableField lf -> {
                    builder.addCode("        case $S -> {\n", lf.name());
                    builder.addCode("$L", InlineLookupTableFieldEmitter.buildSwitchArmBody(lf, tableArg, sf, entry, outputPackage, registry));
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

    /** {@code List<Field<?>>} — the return type of every {@code $fields} entry. */
    private static ParameterizedTypeName listOfFieldWildcard() {
        return ParameterizedTypeName.get(LIST,
            ParameterizedTypeName.get(FIELD, WildcardTypeName.subtypeOf(Object.class)));
    }

    /** The generated {@code <outputPackage>.util.SelectionOccurrences} runtime scaffold. */
    static ClassName selectionOccurrencesClass(String outputPackage) {
        return ClassName.get(outputPackage + ".util", SelectionOccurrencesClassGenerator.CLASS_NAME);
    }

    /**
 * True when any inline {@link ChildField.TableField} / {@link ChildField.LookupTableField}
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
     * The projection a table-parent's {@code $fields} SELECT must include regardless of the
 * user's SDL selection. Two independent axes, co-present:
     *
     * <ul>
     *   <li>{@code reservedFullRow} — some child's DataLoader key wrap is
 * {@link SourceKey.Wrap.TableRecord}, whose key read now rebuilds the typed record
     *       from the whole parent row projected under reserved {@code __src_<col>__} aliases (see
     *       {@code GeneratorUtils.buildKeyExtraction}). When set, {@code $fields} appends every
     *       parent column re-aliased to its reserved name.</li>
     *   <li>{@code baseColumns} — specific columns (possibly none) that must be projected under
     *       their <em>base</em> names because a {@code Wrap.Row}/{@code Wrap.Record} key read
     *       ({@code get(Tables.X.COL)} / {@code into(Tables.X.COL, …)}) or a
     *       {@link ParentRowDemand} correlation read
     *       ({@code parentRecord.get(DSL.name("<src>"), …)}) resolves them by base name.</li>
     * </ul>
     *
     * <p>These were previously a sealed sum, with {@code FullParentRow} absorbing {@code Columns}
     * on the premise that a base-named {@code table.fields()} full-row append subsumed any
     * base-named column list — "a type fact, not a dedup accident". That premise is now false: the
     * full row is projected under reserved aliases, not base names, so it no longer supplies the
     * base-named columns the {@code Wrap.Row}/{@code Wrap.Record}/{@code ParentRowDemand} reads
     * still need. The two facts are genuinely orthogonal axes with no absorbing combine, so this is
     * a product record: both are accumulated and both are emitted. Key columns can then appear twice
     * in the SELECT (once base-named, once reserved); the {@code LinkedHashSet} accumulator dedupes
     * exact base-name repeats, and the minor base/reserved duplication is accepted.
     */
    record RequiredProjection(boolean reservedFullRow, List<ColumnRef> baseColumns) {
        RequiredProjection { baseColumns = List.copyOf(baseColumns); }
    }

    /**
     * Walks the children of a type and surfaces what the parent SELECT must project regardless
     * of the user's SDL selection, as a {@link RequiredProjection}. Sources today:
     *
     * <ul>
     *   <li>Table-parent {@link BatchKeyField} implementers' {@code SourceKey} columns — their
     *       DataLoader fetchers extract the parent-row key off {@code env.getSource()} after the
     *       parent {@code $fields()} SELECT runs (via {@code GeneratorUtils.buildKeyExtraction}),
     *       so every {@code sourceKey().columns()} column must be in that SELECT (the
     *       {@code baseColumns} axis). When the key wrap is {@link SourceKey.Wrap.TableRecord} the
 * requirement instead flips the {@code reservedFullRow} axis:
     *       {@code buildKeyExtraction}'s wrap-forked read for that arm rebuilds the typed record
     *       from the whole parent row projected under reserved {@code __src_<col>__} aliases, and
     *       the documented contract of the typed-record source shape is a fully-populated parent
     *       record. Gated on the wrap, not the field variants, so any future {@code BatchKeyField}
     *       acquiring the wrap gets the right projection for free.</li>
     *   <li>{@link ParentRowDemand} implementers on a table-bound parent — their
     *       {@link ParentRowDemand#parentRowColumns()} columns, read off the parent's
     *       already-materialized row by base name. {@link ChildField.TableMethodField}'s fetcher
     *       ({@code TypeFetcherGenerator.buildChildTableMethodFetcher}) correlates the developer's
     *       returned table against the parent via {@code parentRecord.get(DSL.name("<src>"), …)}
     *       on the resolved FK's source-side columns (only the single-hop FK-derived
     *       {@link JoinStep.Hop} shape demands columns; multi-hop and condition-join paths surface
     *       a runtime {@code UnsupportedOperationException} in the emitter, so the capability
     *       returns none for them rather than synthesising dead columns). The multi-table
     *       polymorphic children ({@link ChildField.InterfaceField} / {@link ChildField.UnionField})
     *       demand the parent-side correlation columns their single-fetch form reads, or the parent
     *       key their batched key extraction reads at list/connection cardinality.</li>
     * </ul>
     *
     * <p>Recurses into {@link ChildField.NestingField} so nested fields whose fetchers need
     * parent-row columns (or the full row) surface that into the outer table-class's
     * {@code $fields}. The nested type shares the parent's table context ({@code tableArg} is
     * threaded through {@code emitSelectionSwitch} unchanged), so a nested full-row requirement
     * correctly projects the <em>outer</em> parent table's fields.
     */
    private static RequiredProjection collectRequiredProjection(List<? extends GraphitronField> fields) {
        boolean reservedFullRow = false;
        var columns = new ArrayList<ColumnRef>();
        for (var f : fields) {
            // Soundness invariant of the blanket BatchKeyField arm below: SourceKey.columns() is
            // parent-side or target-side depending on shape, and this walk runs only under
            // generate()'s TableType/NodeType filter, so every BatchKeyField it sees was
            // classified on a table-backed parent and carries parent-side columns read by
            // buildKeyExtraction. The record-sourced implementers key off the held object via
            // buildRecordParentKeyExtraction instead and may carry target-aligned columns; if one
            // ever leaked into this walk, the blanket arm would silently project wrong columns.
            // Fail at generation time rather than at runtime with a null DataLoader key.
            // The tripwire is the fact predicate (a parent-row-reading capability +
            // Record source shape), not a leaf list — a deliberate leaf-list-to-fact
            // strengthening that also covers the dissolved @tableMethod DTO-parent shape (Record-sourced)
            // through the same gate. Extended to the ParentRowDemand capability: a
            // record-sourced field carrying parent-row demands reads them off the held object,
            // not the parent SELECT, so reaching this table-parent walk is a generator bug.
            if ((f instanceof BatchKeyField || f instanceof ParentRowDemand)
                    && f instanceof ChildField cf && cf.sourceShape() == SourceShape.Record)
                throw new IllegalStateException(
                    "Record-sourced field '" + f.name() + "' (" + f.getClass().getSimpleName()
                        + ") reached a table-parent $fields projection walk; its key / correlation"
                        + " columns are not parent-row columns and must not be force-projected");
            // A null sourceKey means the service method takes no Sources param: the field is a
            // plain per-parent service delegation, not DataLoader-backed, and its fetcher reads
            // no key columns off the parent row — nothing to force-project.
            switch (f) {
                case BatchKeyField bk when bk.sourceKey() != null -> {
                    if (bk.sourceKey().wrap() instanceof SourceKey.Wrap.TableRecord) {
                        // TableRecord key reads rebuild from the reserved-aliased full row, not
                        // base-named columns, so this axis alone flips — no base columns added.
                        reservedFullRow = true;
                    } else {
                        columns.addAll(bk.sourceKey().columns());
                    }
                }
                case ParentRowDemand prd -> columns.addAll(prd.parentRowColumns());
                case ChildField.NestingField nf -> {
                    RequiredProjection nested = collectRequiredProjection(nf.nestedFields());
                    reservedFullRow |= nested.reservedFullRow();
                    columns.addAll(nested.baseColumns());
                }
                default -> { }
            }
        }
        return new RequiredProjection(reservedFullRow, columns.stream().distinct().toList());
    }

}
