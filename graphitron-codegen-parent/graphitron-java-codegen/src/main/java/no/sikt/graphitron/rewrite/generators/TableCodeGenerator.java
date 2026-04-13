package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.javapoet.WildcardTypeName;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.TableRef;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.List;

import static javax.lang.model.element.Modifier.PUBLIC;
import static javax.lang.model.element.Modifier.STATIC;

/**
 * Generates a {@link TypeSpec} for one table class in {@code rewrite.tables}.
 *
 * <p>The class is named after the SQL table (PascalCase, e.g. {@code Film} for table
 * {@code film}, {@code FilmActor} for table {@code film_actor}). This is distinct from the
 * GraphQL type name, which may differ.
 *
 * <p>Each class contains four scope-establishing stub methods covering SQL projection:
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
 */
public class TableCodeGenerator {

    private static final ClassName RESULT         = ClassName.get("org.jooq", "Result");
    private static final ClassName RECORD         = ClassName.get("org.jooq", "Record");
    private static final ClassName ROW            = ClassName.get("org.jooq", "Row");
    private static final ClassName FIELD          = ClassName.get("org.jooq", "Field");
    private static final ClassName CONDITION      = ClassName.get("org.jooq", "Condition");
    private static final ClassName SORT_FIELD     = ClassName.get("org.jooq", "SortField");
    private static final ClassName DSL_CONTEXT    = ClassName.get("org.jooq", "DSLContext");
    private static final ClassName LIST           = ClassName.get(List.class);
    private static final ClassName ENV            = ClassName.get("graphql.schema", "DataFetchingEnvironment");
    private static final ClassName SELECTION_SET  = ClassName.get("graphql.schema", "DataFetchingFieldSelectionSet");
    private static final ClassName SELECTED_FIELD = ClassName.get("graphql.schema", "SelectedField");
    private static final ClassName GRAPHITRON_CONTEXT = ClassName.get("no.sikt.graphql", "GraphitronContext");
    private static final ClassName ARRAY_LIST = ClassName.get(ArrayList.class);

    /**
     * Maps a GraphQL field name to its resolved jOOQ column java name, used to generate the
     * {@code fields()} method that assembles the SELECT list based on the selection set.
     */
    public record ScalarColumn(String graphqlFieldName, String jooqColumnJavaName) {}

    /**
     * Generates the table class with:
     * <ul>
     *   <li>{@code fields()} — selection-set-aware SELECT list assembly</li>
     *   <li>{@code selectMany} / {@code selectOne} — root query methods using {@code fields()}</li>
     *   <li>Remaining overloads as stubs</li>
     * </ul>
     *
     * @param tableRef the resolved table reference with jOOQ field/class names
     * @param scalarColumns the scalar columns to include in {@code fields()}, in declaration order
     */
    public TypeSpec generate(TableRef tableRef, List<ScalarColumn> scalarColumns) {
        return TypeSpec.classBuilder(tableRef.javaClassName())
            .addModifiers(Modifier.PUBLIC)
            .addMethod(buildFieldsMethod(tableRef, scalarColumns))
            .addMethod(buildSelectManyMethod(tableRef))
            .addMethod(buildSelectOneMethod(tableRef))
            .addMethod(buildSelectManyFromRowServiceMethod())
            .addMethod(buildSelectOneFromRowServiceMethod())
            .addMethod(buildSelectManyFromRecordServiceMethod())
            .addMethod(buildSelectOneFromRecordServiceMethod())
            .addMethod(buildSubselectManyMethod())
            .addMethod(buildSubselectOneMethod())
            .build();
    }

    /**
     * Generates a {@code fields()} method that assembles the SELECT list based on the
     * {@link graphql.schema.DataFetchingFieldSelectionSet}. Only columns whose GraphQL field
     * name appears in the selection set are included.
     *
     * <p>Generated code pattern:
     * <pre>{@code
     * public static List<Field<?>> fields(DataFetchingFieldSelectionSet sel) {
     *     var table = Tables.FILM;
     *     var fields = new ArrayList<Field<?>>();
     *     if (sel.contains("title"))  fields.add(table.TITLE);
     *     if (sel.contains("filmId")) fields.add(table.FILM_ID);
     *     return fields;
     * }
     * }</pre>
     */
    private MethodSpec buildFieldsMethod(TableRef tableRef, List<ScalarColumn> scalarColumns) {
        var tablesClass = tablesClassName();
        var fieldWildcard = ParameterizedTypeName.get(FIELD, WildcardTypeName.subtypeOf(Object.class));
        var listOfField = ParameterizedTypeName.get(LIST, fieldWildcard);

        var builder = MethodSpec.methodBuilder("fields")
            .addModifiers(PUBLIC, STATIC)
            .returns(listOfField)
            .addParameter(SELECTION_SET, "sel")
            .addStatement("var table = $T.$L", tablesClass, tableRef.javaFieldName())
            .addStatement("var fields = new $T<$T>()", ARRAY_LIST, fieldWildcard);

        for (var col : scalarColumns) {
            builder.addStatement("if (sel.contains($S)) fields.add(table.$L)",
                col.graphqlFieldName(), col.jooqColumnJavaName());
        }

        builder.addStatement("return fields");
        return builder.build();
    }

    /**
     * Generates a {@code selectMany} method that uses {@code fields(sel)} for the SELECT list.
     */
    private MethodSpec buildSelectManyMethod(TableRef tableRef) {
        var tablesClass = tablesClassName();
        return MethodSpec.methodBuilder("selectMany")
            .addModifiers(PUBLIC, STATIC)
            .returns(ParameterizedTypeName.get(RESULT, RECORD))
            .addParameter(ENV, "env")
            .addParameter(CONDITION, "condition")
            .addParameter(sortFieldList(), "orderBy")
            .addStatement("$T dsl = (($T) env.getGraphQlContext().get($S)).getDslContext()",
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
    private MethodSpec buildSelectOneMethod(TableRef tableRef) {
        var tablesClass = tablesClassName();
        return MethodSpec.methodBuilder("selectOne")
            .addModifiers(PUBLIC, STATIC)
            .returns(RECORD)
            .addParameter(ENV, "env")
            .addParameter(CONDITION, "condition")
            .addStatement("$T dsl = (($T) env.getGraphQlContext().get($S)).getDslContext()",
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
        return ClassName.get(GeneratorConfig.outputPackage() + ".tables", "Tables");
    }

    /** Row-keyed service overload: {@code selectMany(List<? extends Row>, SelectedField, List<?>)}. */
    private MethodSpec buildSelectManyFromRowServiceMethod() {
        var listOfRecord = ParameterizedTypeName.get(LIST, RECORD);
        return MethodSpec.methodBuilder("selectMany")
            .addModifiers(PUBLIC, STATIC)
            .returns(ParameterizedTypeName.get(LIST, listOfRecord))
            .addParameter(ParameterizedTypeName.get(LIST, WildcardTypeName.subtypeOf(ROW)), "keys")
            .addParameter(SELECTED_FIELD, "sel")
            .addParameter(ParameterizedTypeName.get(LIST, WildcardTypeName.subtypeOf(Object.class)), "serviceRecords")
            .addStatement("throw new $T()", UnsupportedOperationException.class)
            .build();
    }

    /** Row-keyed service overload: {@code selectOne(List<? extends Row>, SelectedField, Object)}. */
    private MethodSpec buildSelectOneFromRowServiceMethod() {
        return MethodSpec.methodBuilder("selectOne")
            .addModifiers(PUBLIC, STATIC)
            .returns(ParameterizedTypeName.get(LIST, RECORD))
            .addParameter(ParameterizedTypeName.get(LIST, WildcardTypeName.subtypeOf(ROW)), "keys")
            .addParameter(SELECTED_FIELD, "sel")
            .addParameter(Object.class, "serviceRecord")
            .addStatement("throw new $T()", UnsupportedOperationException.class)
            .build();
    }

    /**
     * Record-keyed service overload: {@code selectMany(List<? extends Record>, SelectedField, List<?>)}.
     * Handles both {@code RecordN<T>}-keyed and {@code TableRecord}-keyed callers (both implement
     * {@code org.jooq.Record}).
     */
    private MethodSpec buildSelectManyFromRecordServiceMethod() {
        var listOfRecord = ParameterizedTypeName.get(LIST, RECORD);
        return MethodSpec.methodBuilder("selectMany")
            .addModifiers(PUBLIC, STATIC)
            .returns(ParameterizedTypeName.get(LIST, listOfRecord))
            .addParameter(ParameterizedTypeName.get(LIST, WildcardTypeName.subtypeOf(RECORD)), "keys")
            .addParameter(SELECTED_FIELD, "sel")
            .addParameter(ParameterizedTypeName.get(LIST, WildcardTypeName.subtypeOf(Object.class)), "serviceRecords")
            .addStatement("throw new $T()", UnsupportedOperationException.class)
            .build();
    }

    /**
     * Record-keyed service overload: {@code selectOne(List<? extends Record>, SelectedField, Object)}.
     * Handles both {@code RecordN<T>}-keyed and {@code TableRecord}-keyed callers.
     */
    private MethodSpec buildSelectOneFromRecordServiceMethod() {
        return MethodSpec.methodBuilder("selectOne")
            .addModifiers(PUBLIC, STATIC)
            .returns(ParameterizedTypeName.get(LIST, RECORD))
            .addParameter(ParameterizedTypeName.get(LIST, WildcardTypeName.subtypeOf(RECORD)), "keys")
            .addParameter(SELECTED_FIELD, "sel")
            .addParameter(Object.class, "serviceRecord")
            .addStatement("throw new $T()", UnsupportedOperationException.class)
            .build();
    }

    private MethodSpec buildSubselectManyMethod() {
        return MethodSpec.methodBuilder("subselectMany")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(ParameterizedTypeName.get(FIELD, ParameterizedTypeName.get(RESULT, RECORD)))
            .addParameter(SELECTION_SET, "sel")
            .addParameter(CONDITION, "condition")
            .addParameter(sortFieldList(), "orderBy")
            .addStatement("throw new $T()", UnsupportedOperationException.class)
            .build();
    }

    private MethodSpec buildSubselectOneMethod() {
        return MethodSpec.methodBuilder("subselectOne")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(ParameterizedTypeName.get(FIELD, RECORD))
            .addParameter(SELECTION_SET, "sel")
            .addParameter(CONDITION, "condition")
            .addStatement("throw new $T()", UnsupportedOperationException.class)
            .build();
    }

    private static ParameterizedTypeName sortFieldList() {
        return ParameterizedTypeName.get(LIST,
            ParameterizedTypeName.get(SORT_FIELD, WildcardTypeName.subtypeOf(Object.class)));
    }
}
