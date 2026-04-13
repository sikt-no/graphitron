package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.javapoet.WildcardTypeName;
import no.sikt.graphitron.rewrite.model.TableRef;

import javax.lang.model.element.Modifier;
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

    /**
     * Generates the table class with real method bodies for {@code selectMany} and
     * {@code selectOne}, and stub bodies for the remaining overloads.
     *
     * @param tableRef the resolved table reference with jOOQ field/class names
     */
    public TypeSpec generate(TableRef tableRef) {
        return TypeSpec.classBuilder(tableRef.javaClassName())
            .addModifiers(Modifier.PUBLIC)
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
     * Generates a {@code selectMany} method that:
     * <ol>
     *   <li>Obtains a {@link org.jooq.DSLContext} from the {@code GraphitronContext}.</li>
     *   <li>Selects all columns from the table using {@code table.fields()}.</li>
     *   <li>Applies the WHERE {@code condition} (when non-null).</li>
     *   <li>Applies the ORDER BY {@code orderBy} list.</li>
     *   <li>Fetches and returns the result.</li>
     * </ol>
     */
    private MethodSpec buildSelectManyMethod(TableRef tableRef) {
        var tablesClass = tablesClassName();
        return MethodSpec.methodBuilder("selectMany")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
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
                .add(".select(table.fields())\n")
                .add(".from(table)\n")
                .add(".where(condition)\n")
                .add(".orderBy(orderBy)\n")
                .add(".fetch();\n")
                .unindent()
                .build())
            .build();
    }

    /**
     * Generates a {@code selectOne} method that selects all columns from the table,
     * applies the WHERE {@code condition}, and fetches a single row.
     */
    private MethodSpec buildSelectOneMethod(TableRef tableRef) {
        var tablesClass = tablesClassName();
        return MethodSpec.methodBuilder("selectOne")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(RECORD)
            .addParameter(ENV, "env")
            .addParameter(CONDITION, "condition")
            .addStatement("$T dsl = (($T) env.getGraphQlContext().get($S)).getDslContext()",
                DSL_CONTEXT, GRAPHITRON_CONTEXT, "graphitronContext")
            .addStatement("var table = $T.$L", tablesClass, tableRef.javaFieldName())
            .addCode(CodeBlock.builder()
                .add("return dsl\n")
                .indent()
                .add(".select(table.fields())\n")
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
