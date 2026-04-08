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
 * <p>Each class contains four scope-establishing stub methods:
 * <ul>
 *   <li>{@code selectMany} — executes a new SQL statement and returns all rows
 *       (list root queries, DataLoaders)</li>
 *   <li>{@code selectOne} — executes a new SQL statement and returns a single row
 *       (single root queries, single lookups)</li>
 *   <li>{@code subselectMany} — contributes a multiset subquery to an existing statement,
 *       returning many rows (inline list {@code TableField})</li>
 *   <li>{@code subselectOne} — contributes a scalar subquery to an existing statement,
 *       returning a single row (inline single {@code TableField})</li>
 * </ul>
 *
 * <p>All stubs throw {@link UnsupportedOperationException} until their bodies are filled in by
 * subsequent deliverables.
 */
public class TableCodeGenerator {

    private static final ClassName RESULT        = ClassName.get("org.jooq", "Result");
    private static final ClassName RECORD        = ClassName.get("org.jooq", "Record");
    private static final ClassName FIELD         = ClassName.get("org.jooq", "Field");
    private static final ClassName CONDITION     = ClassName.get("org.jooq", "Condition");
    private static final ClassName SORT_FIELD    = ClassName.get("org.jooq", "SortField");
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

    private static ParameterizedTypeName sortFieldList() {
        return ParameterizedTypeName.get(LIST,
            ParameterizedTypeName.get(SORT_FIELD, WildcardTypeName.subtypeOf(Object.class)));
    }
}
