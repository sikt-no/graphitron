package no.sikt.graphitron.rewrite.generators.fields;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.javapoet.WildcardTypeName;

import javax.lang.model.element.Modifier;
import java.util.List;

/**
 * Generates a {@link TypeSpec} for one table class in {@code rewrite.tables}.
 *
 * <p>The class is named after the SQL table (PascalCase, e.g. {@code Film} for table
 * {@code film}, {@code FilmActor} for table {@code film_actor}). This is distinct from the
 * GraphQL type name, which may differ.
 *
 * <p>Each class contains seven scope-establishing stub methods:
 * <ul>
 *   <li>{@code selectMany} — executes a new SQL statement and returns all rows
 *       (list root queries)</li>
 *   <li>{@code selectOne} — executes a new SQL statement and returns a single row
 *       (single root queries)</li>
 *   <li>{@code subselectMany} — contributes a multiset subquery to an existing statement,
 *       returning many rows (inline list {@code TableField})</li>
 *   <li>{@code subselectOne} — contributes a scalar subquery to an existing statement,
 *       returning a single row (inline single {@code TableField})</li>
 *   <li>{@code loadManyBySource} — executes an indexed VALUES JOIN against a derived source
 *       table built from parent records; used by {@code @splitQuery} DataLoaders when the
 *       field has no {@code @lookupKey} arguments</li>
 *   <li>{@code loadManyByTarget} — executes an indexed VALUES JOIN against a derived target
 *       table built from {@code @lookupKey} argument values; used by {@code LookupQueryField}
 *       DataLoaders</li>
 *   <li>{@code loadMany} — executes two indexed VALUES JOINs (one for the derived source
 *       table, one for the derived target table) and returns N×M results in row-major order;
 *       used by {@code @splitQuery} DataLoaders when the field also has {@code @lookupKey}
 *       arguments</li>
 * </ul>
 *
 * <p>All stubs throw {@link UnsupportedOperationException} until their bodies are filled in by
 * subsequent deliverables.
 */
public class TableCodeGenerator {

    private static final ClassName RESULT        = ClassName.get("org.jooq", "Result");
    private static final ClassName RECORD        = ClassName.get("org.jooq", "Record");
    private static final ClassName ROW           = ClassName.get("org.jooq", "Row");
    private static final ClassName FIELD         = ClassName.get("org.jooq", "Field");
    private static final ClassName CONDITION     = ClassName.get("org.jooq", "Condition");
    private static final ClassName SORT_FIELD    = ClassName.get("org.jooq", "SortField");
    private static final ClassName DSL_CONTEXT   = ClassName.get("org.jooq", "DSLContext");
    private static final ClassName LIST          = ClassName.get(List.class);
    private static final ClassName ENV           = ClassName.get("graphql.schema", "DataFetchingEnvironment");
    private static final ClassName SELECTION_SET = ClassName.get("graphql.schema", "DataFetchingFieldSelectionSet");

    public TypeSpec generate(String tableName) {
        return TypeSpec.classBuilder(tableName)
            .addModifiers(Modifier.PUBLIC)
            .addMethod(buildSelectManyMethod())
            .addMethod(buildSelectOneMethod())
            .addMethod(buildSubselectManyMethod())
            .addMethod(buildSubselectOneMethod())
            .addMethod(buildLoadManyBySourceMethod())
            .addMethod(buildLoadManyByTargetMethod())
            .addMethod(buildLoadManyMethod())
            .build();
    }

    private MethodSpec buildSelectManyMethod() {
        return MethodSpec.methodBuilder("selectMany")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(ParameterizedTypeName.get(RESULT, RECORD))
            .addParameter(ENV, "env")
            .addParameter(CONDITION, "condition")
            .addParameter(sortFieldList(), "orderBy")
            .addStatement("throw new $T()", UnsupportedOperationException.class)
            .build();
    }

    private MethodSpec buildSelectOneMethod() {
        return MethodSpec.methodBuilder("selectOne")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(RECORD)
            .addParameter(ENV, "env")
            .addParameter(CONDITION, "condition")
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

    /**
     * Generates the {@code loadManyBySource} stub used by {@code @splitQuery} DataLoaders when
     * the field has no {@code @lookupKey} arguments. Receives a batch of source key rows built
     * from parent records, executes an indexed VALUES JOIN against a derived source table, and
     * returns results positionally aligned with the input.
     *
     * <pre>{@code
     * public static List<List<Record>> loadManyBySource(DSLContext ctx, List<Row> sourceKeys) {
     *     throw new UnsupportedOperationException();
     * }
     * }</pre>
     */
    private MethodSpec buildLoadManyBySourceMethod() {
        return MethodSpec.methodBuilder("loadManyBySource")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(ParameterizedTypeName.get(LIST, ParameterizedTypeName.get(LIST, RECORD)))
            .addParameter(DSL_CONTEXT, "ctx")
            .addParameter(ParameterizedTypeName.get(LIST, ROW), "sourceKeys")
            .addStatement("throw new $T()", UnsupportedOperationException.class)
            .build();
    }

    /**
     * Generates the {@code loadManyByTarget} stub used by {@code LookupQueryField} DataLoaders.
     * Receives a batch of target key rows built from {@code @lookupKey} argument values, executes
     * an indexed VALUES JOIN against a derived target table, and returns results positionally
     * aligned with the input.
     *
     * <pre>{@code
     * public static List<List<Record>> loadManyByTarget(DSLContext ctx, List<Row> targetKeys) {
     *     throw new UnsupportedOperationException();
     * }
     * }</pre>
     */
    private MethodSpec buildLoadManyByTargetMethod() {
        return MethodSpec.methodBuilder("loadManyByTarget")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(ParameterizedTypeName.get(LIST, ParameterizedTypeName.get(LIST, RECORD)))
            .addParameter(DSL_CONTEXT, "ctx")
            .addParameter(ParameterizedTypeName.get(LIST, ROW), "targetKeys")
            .addStatement("throw new $T()", UnsupportedOperationException.class)
            .build();
    }

    /**
     * Generates the {@code loadMany} stub used by {@code @splitQuery} DataLoaders when the field
     * also has {@code @lookupKey} arguments. Executes two indexed VALUES JOINs — one for the
     * derived source table (parent records) and one for the derived target table ({@code @lookupKey}
     * args) — and returns N×M results in row-major order: position {@code (i * targetKeys.size() + j)}
     * holds the results for {@code (sourceKeys[i], targetKeys[j])}.
     *
     * <pre>{@code
     * public static List<List<Record>> loadMany(DSLContext ctx, List<Row> sourceKeys, List<Row> targetKeys) {
     *     throw new UnsupportedOperationException();
     * }
     * }</pre>
     */
    private MethodSpec buildLoadManyMethod() {
        return MethodSpec.methodBuilder("loadMany")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(ParameterizedTypeName.get(LIST, ParameterizedTypeName.get(LIST, RECORD)))
            .addParameter(DSL_CONTEXT, "ctx")
            .addParameter(ParameterizedTypeName.get(LIST, ROW), "sourceKeys")
            .addParameter(ParameterizedTypeName.get(LIST, ROW), "targetKeys")
            .addStatement("throw new $T()", UnsupportedOperationException.class)
            .build();
    }

    private static ParameterizedTypeName sortFieldList() {
        return ParameterizedTypeName.get(LIST,
            ParameterizedTypeName.get(SORT_FIELD, WildcardTypeName.subtypeOf(Object.class)));
    }
}
