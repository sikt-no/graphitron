package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.javapoet.WildcardTypeName;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.TableRef;

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
 * If two GraphQL types map to the same SQL table, each gets its own type class with its own
 * {@code fields()}, {@code selectMany}, etc.
 *
 * <p>Each class contains a {@code fields()} method that assembles the SELECT list, plus
 * scope-establishing stub methods covering SQL projection:
 * <ul>
 *   <li>{@code selectMany} — executes a new SQL statement and returns all rows
 *       (list root queries)</li>
 *   <li>{@code selectOne} — executes a new SQL statement and returns a single row
 *       (single root queries)</li>
 *   <li>{@code subselectMany} — contributes a multiset subquery to an existing statement,
 *       returning many rows (inline list {@code TableField})</li>
 *   <li>{@code subselectOne} — contributes a scalar subquery to an existing statement,
 *       returning a single row (inline single {@code TableField})</li>
 * </ul>
 *
 * <p>DataLoader batch methods ({@code load*} / {@code lookup*}) are generated bespoke
 * per-field and live in {@code rewrite.types.<TypeName>Fields} alongside their data fetchers,
 * not in this class.
 *
 * <p>All stubs throw {@link UnsupportedOperationException} until their bodies are filled in by
 * subsequent deliverables.
 *
 * <p>Generated files are placed in the {@code rewrite.types} sub-package of the configured
 * output package.
 */
public class TypeClassGenerator {

    private static final ClassName RESULT         = ClassName.get("org.jooq", "Result");
    private static final ClassName RECORD         = ClassName.get("org.jooq", "Record");
    private static final ClassName ROW            = ClassName.get("org.jooq", "Row");
    private static final ClassName FIELD          = ClassName.get("org.jooq", "Field");
    private static final ClassName CONDITION      = ClassName.get("org.jooq", "Condition");
    private static final ClassName SORT_FIELD     = ClassName.get("org.jooq", "SortField");
    private static final ClassName DSL_CONTEXT    = ClassName.get("org.jooq", "DSLContext");
    private static final ClassName DSL            = ClassName.get("org.jooq.impl", "DSL");
    private static final ClassName LIST           = ClassName.get(List.class);
    private static final ClassName ENV            = ClassName.get("graphql.schema", "DataFetchingEnvironment");
    private static final ClassName SELECTION_SET  = ClassName.get("graphql.schema", "DataFetchingFieldSelectionSet");
    private static final ClassName SELECTED_FIELD = ClassName.get("graphql.schema", "SelectedField");
    private static final ClassName GRAPHITRON_CONTEXT = ClassName.get("no.sikt.graphql", "GraphitronContext");
    private static final ClassName ARRAY_LIST = ClassName.get(ArrayList.class);

    public static List<TypeSpec> generate(GraphitronSchema schema) {
        return schema.types().entrySet().stream()
            .filter(e -> e.getValue() instanceof GraphitronType.TableType
                      || e.getValue() instanceof GraphitronType.NodeType)
            .map(java.util.Map.Entry::getKey)
            .sorted()
            .map(typeName -> generateForType(schema, typeName))
            .toList();
    }

    private static TypeSpec generateForType(GraphitronSchema schema, String typeName) {
        var type = (GraphitronType.TableBackedType) schema.type(typeName);
        var columnFields = schema.fieldsOf(typeName).stream()
            .filter(f -> f instanceof ChildField.ColumnField)
            .map(f -> (ChildField.ColumnField) f)
            .sorted(Comparator.comparing(GraphitronField::name))
            .toList();
        return buildTypeSpec(typeName, type.table(), columnFields);
    }

    /**
     * @param typeName      the GraphQL type name (used as the class name)
     * @param tableRef      the resolved table reference with jOOQ field/class names
     * @param columnFields  the scalar column fields to include in {@code fields()}, in declaration order
     */
    static TypeSpec buildTypeSpec(String typeName, TableRef tableRef, List<ChildField.ColumnField> columnFields) {
        return TypeSpec.classBuilder(typeName)
            .addModifiers(Modifier.PUBLIC)
            .addMethod(buildFieldsMethod(tableRef, columnFields))
            .addMethod(buildSelectManyMethod(tableRef))
            .addMethod(buildSelectOneMethod(tableRef))
            .addMethod(buildSelectManyFromRowServiceMethod())
            .addMethod(buildSelectOneFromRowServiceMethod())
            .addMethod(buildSelectManyFromRecordServiceMethod())
            .addMethod(buildSelectOneFromRecordServiceMethod())
            .addMethod(buildSubselectManyMethod(tableRef))
            .addMethod(buildSubselectOneMethod(tableRef))
            .build();
    }

    /**
     * Generates a {@code fields()} method that assembles the SELECT list for one level of the
     * query from a {@link graphql.schema.DataFetchingFieldSelectionSet}.
     *
     * <p>Uses {@code sel.getFieldsGroupedByResultKey()} to iterate the selected fields. Each
     * entry is matched by {@code SelectedField.getName()} (the schema field name, not the alias)
     * against the known scalar columns. This correctly handles aliased fields — e.g.
     * {@code myTitle: title} has result key {@code "myTitle"} but name {@code "title"}.
     *
     * <p>The entry point is {@code env.getSelectionSet()} (root level). For nested fields,
     * callers drill down via {@code selectedField.getSelectionSet()} at each level.
     *
     * <p>When inline nested fields are added (G5), the {@code SelectedField} from each entry
     * provides {@code getArguments()} for WHERE clauses and {@code getSelectionSet()} for
     * recursive drill-down.
     */
    private static MethodSpec buildFieldsMethod(TableRef tableRef, List<ChildField.ColumnField> columnFields) {
        var tablesClass = tablesClassName();
        var fieldWildcard = ParameterizedTypeName.get(FIELD, WildcardTypeName.subtypeOf(Object.class));
        var listOfField = ParameterizedTypeName.get(LIST, fieldWildcard);

        var builder = MethodSpec.methodBuilder("fields")
            .addModifiers(PUBLIC, STATIC)
            .returns(listOfField)
            .addParameter(SELECTION_SET, "sel")
            .addStatement("var table = $T.$L", tablesClass, tableRef.javaFieldName())
            .addStatement("var fields = new $T<$T>()", ARRAY_LIST, fieldWildcard);

        builder.addCode("for (var entry : sel.getFieldsGroupedByResultKey().entrySet()) {\n");
        builder.addCode("    var sf = entry.getValue().get(0);\n");
        builder.addCode("    switch (sf.getName()) {\n");
        for (var cf : columnFields) {
            builder.addCode("        case $S -> fields.add(table.$L);\n",
                cf.name(), cf.column().javaName());
        }
        builder.addCode("        default -> { } // nested/unhandled fields\n");
        builder.addCode("    }\n");
        builder.addCode("}\n");

        builder.addStatement("return fields");
        return builder.build();
    }

    /**
     * Generates a {@code selectMany} method that uses {@code fields(sel)} for the SELECT list.
     */
    private static MethodSpec buildSelectManyMethod(TableRef tableRef) {
        var tablesClass = tablesClassName();
        return MethodSpec.methodBuilder("selectMany")
            .addModifiers(PUBLIC, STATIC)
            .returns(ParameterizedTypeName.get(RESULT, RECORD))
            .addParameter(ENV, "env")
            .addParameter(CONDITION, "condition")
            .addParameter(sortFieldList(), "orderBy")
            .addStatement("$T dsl = (($T) env.getGraphQlContext().get($S)).getDslContext(env)",
                DSL_CONTEXT, GRAPHITRON_CONTEXT, "graphitronContext")
            .addStatement("var table = $T.$L", tablesClass, tableRef.javaFieldName())
            .addCode(CodeBlock.builder()
                .add("return dsl\n")
                .indent()
                .add(".select(fields(env.getSelectionSet()))\n")
                .add(".from(table)\n")
                .add(".where(condition)\n")
                .add(".orderBy(orderBy)\n")
                .add(".fetch();\n")
                .unindent()
                .build())
            .build();
    }

    /**
     * Generates a {@code selectOne} method that uses {@code fields(sel)} for the SELECT list.
     */
    private static MethodSpec buildSelectOneMethod(TableRef tableRef) {
        var tablesClass = tablesClassName();
        return MethodSpec.methodBuilder("selectOne")
            .addModifiers(PUBLIC, STATIC)
            .returns(RECORD)
            .addParameter(ENV, "env")
            .addParameter(CONDITION, "condition")
            .addStatement("$T dsl = (($T) env.getGraphQlContext().get($S)).getDslContext(env)",
                DSL_CONTEXT, GRAPHITRON_CONTEXT, "graphitronContext")
            .addStatement("var table = $T.$L", tablesClass, tableRef.javaFieldName())
            .addCode(CodeBlock.builder()
                .add("return dsl\n")
                .indent()
                .add(".select(fields(env.getSelectionSet()))\n")
                .add(".from(table)\n")
                .add(".where(condition)\n")
                .add(".fetchOne();\n")
                .unindent()
                .build())
            .build();
    }

    private static ClassName tablesClassName() {
        return ClassName.get(GeneratorConfig.getGeneratedJooqPackage(), "Tables");
    }

    /** Row-keyed service overload: {@code selectManyByRowKeys(List<? extends Row>, env, sel, List<?>)}. */
    private static MethodSpec buildSelectManyFromRowServiceMethod() {
        var listOfRecord = ParameterizedTypeName.get(LIST, RECORD);
        return MethodSpec.methodBuilder("selectManyByRowKeys")
            .addModifiers(PUBLIC, STATIC)
            .returns(ParameterizedTypeName.get(LIST, listOfRecord))
            .addParameter(ParameterizedTypeName.get(LIST, WildcardTypeName.subtypeOf(ROW)), "keys")
            .addParameter(ENV, "env")
            .addParameter(SELECTED_FIELD, "sel")
            .addParameter(ParameterizedTypeName.get(LIST, WildcardTypeName.subtypeOf(Object.class)), "serviceRecords")
            .addStatement("throw new $T()", UnsupportedOperationException.class)
            .build();
    }

    /** Row-keyed service overload: {@code selectOneByRowKeys(List<? extends Row>, env, sel, Object)}. */
    private static MethodSpec buildSelectOneFromRowServiceMethod() {
        return MethodSpec.methodBuilder("selectOneByRowKeys")
            .addModifiers(PUBLIC, STATIC)
            .returns(ParameterizedTypeName.get(LIST, RECORD))
            .addParameter(ParameterizedTypeName.get(LIST, WildcardTypeName.subtypeOf(ROW)), "keys")
            .addParameter(ENV, "env")
            .addParameter(SELECTED_FIELD, "sel")
            .addParameter(Object.class, "serviceRecord")
            .addStatement("throw new $T()", UnsupportedOperationException.class)
            .build();
    }

    /**
     * Record-keyed service overload: {@code selectManyByRecordKeys(List<? extends Record>, env, sel, List<?>)}.
     * Handles both {@code RecordN<T>}-keyed and {@code TableRecord}-keyed callers (both implement
     * {@code org.jooq.Record}).
     */
    private static MethodSpec buildSelectManyFromRecordServiceMethod() {
        var listOfRecord = ParameterizedTypeName.get(LIST, RECORD);
        return MethodSpec.methodBuilder("selectManyByRecordKeys")
            .addModifiers(PUBLIC, STATIC)
            .returns(ParameterizedTypeName.get(LIST, listOfRecord))
            .addParameter(ParameterizedTypeName.get(LIST, WildcardTypeName.subtypeOf(RECORD)), "keys")
            .addParameter(ENV, "env")
            .addParameter(SELECTED_FIELD, "sel")
            .addParameter(ParameterizedTypeName.get(LIST, WildcardTypeName.subtypeOf(Object.class)), "serviceRecords")
            .addStatement("throw new $T()", UnsupportedOperationException.class)
            .build();
    }

    /**
     * Record-keyed service overload: {@code selectOneByRecordKeys(List<? extends Record>, env, sel, Object)}.
     * Handles both {@code RecordN<T>}-keyed and {@code TableRecord}-keyed callers.
     */
    private static MethodSpec buildSelectOneFromRecordServiceMethod() {
        return MethodSpec.methodBuilder("selectOneByRecordKeys")
            .addModifiers(PUBLIC, STATIC)
            .returns(ParameterizedTypeName.get(LIST, RECORD))
            .addParameter(ParameterizedTypeName.get(LIST, WildcardTypeName.subtypeOf(RECORD)), "keys")
            .addParameter(ENV, "env")
            .addParameter(SELECTED_FIELD, "sel")
            .addParameter(Object.class, "serviceRecord")
            .addStatement("throw new $T()", UnsupportedOperationException.class)
            .build();
    }

    /**
     * Subselect overload for inline list fields:
     * {@code subselectMany(env, sel, condition, orderBy)}.
     *
     * <p>Returns a jOOQ {@code multiset} expression — a passive field expression that is embedded
     * into the parent SELECT and executed in the same round-trip. The alias
     * ({@code sel.getResultKey()}) tells the parent record which key to use when storing the
     * nested result.
     *
     * <p>{@code env} is included for consistency with all table methods (context arguments, tenant
     * ID); the plain multiset body does not need it.
     */
    private static MethodSpec buildSubselectManyMethod(TableRef tableRef) {
        var tablesClass = tablesClassName();
        return MethodSpec.methodBuilder("subselectMany")
            .addModifiers(PUBLIC, STATIC)
            .returns(ParameterizedTypeName.get(FIELD, ParameterizedTypeName.get(RESULT, RECORD)))
            .addParameter(ENV, "env")
            .addParameter(SELECTED_FIELD, "sel")
            .addParameter(CONDITION, "condition")
            .addParameter(sortFieldList(), "orderBy")
            .addStatement("var table = $T.$L", tablesClass, tableRef.javaFieldName())
            .addStatement(
                "return $T.multiset($T.select(fields(sel.getSelectionSet())).from(table).where(condition).orderBy(orderBy)).as(sel.getResultKey())",
                DSL, DSL)
            .build();
    }

    /**
     * Subselect overload for inline single fields:
     * {@code subselectOne(env, sel, condition)}.
     *
     * <p>Uses {@code multiset(...).limit(1)} and {@code convertFrom} to peel the first
     * (only) row out of the nested result, returning {@code Field<Record>} rather than
     * {@code Field<Result<Record>>}. Returns {@code null} when the multiset is empty
     * (i.e. the join produced no row — treated as a nullable single).
     */
    private static MethodSpec buildSubselectOneMethod(TableRef tableRef) {
        var tablesClass = tablesClassName();
        return MethodSpec.methodBuilder("subselectOne")
            .addModifiers(PUBLIC, STATIC)
            .returns(ParameterizedTypeName.get(FIELD, RECORD))
            .addParameter(ENV, "env")
            .addParameter(SELECTED_FIELD, "sel")
            .addParameter(CONDITION, "condition")
            .addStatement("var table = $T.$L", tablesClass, tableRef.javaFieldName())
            .addStatement(
                "return $T.multiset($T.select(fields(sel.getSelectionSet())).from(table).where(condition).limit(1)).as(sel.getResultKey()).convertFrom(r -> r.isEmpty() ? null : r.get(0))",
                DSL, DSL)
            .build();
    }

    private static ParameterizedTypeName sortFieldList() {
        return ParameterizedTypeName.get(LIST,
            ParameterizedTypeName.get(SORT_FIELD, WildcardTypeName.subtypeOf(Object.class)));
    }
}
