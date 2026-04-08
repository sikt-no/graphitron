package no.sikt.graphitron.rewrite.generators.fields;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.javapoet.WildcardTypeName;

import javax.lang.model.element.Modifier;
import java.util.List;

/**
 * Generates a {@link TypeSpec} for one {@code <TypeName>Fields} class.
 *
 * <p>Each class contains four scope-establishing stub methods:
 * <ul>
 *   <li>{@code <typeName>SelectMany} — executes a new SQL statement and returns all rows
 *       (list root queries, DataLoaders)</li>
 *   <li>{@code <typeName>SelectOne} — executes a new SQL statement and returns a single row
 *       (single root queries, single lookups)</li>
 *   <li>{@code <typeName>SubselectMany} — contributes a multiset subquery to an existing statement,
 *       returning many rows (inline list {@code TableField})</li>
 *   <li>{@code <typeName>SubselectOne} — contributes a scalar subquery to an existing statement,
 *       returning a single row (inline single {@code TableField})</li>
 * </ul>
 *
 * <p>All stubs throw {@link UnsupportedOperationException} until their bodies are filled in by
 * subsequent deliverables.
 */
public class FieldsCodeGenerator {

    private static final ClassName RESULT            = ClassName.get("org.jooq", "Result");
    private static final ClassName RECORD            = ClassName.get("org.jooq", "Record");
    private static final ClassName FIELD             = ClassName.get("org.jooq", "Field");
    private static final ClassName CONDITION         = ClassName.get("org.jooq", "Condition");
    private static final ClassName SORT_FIELD        = ClassName.get("org.jooq", "SortField");
    private static final ClassName LIST              = ClassName.get(List.class);
    private static final ClassName ENV               = ClassName.get("graphql.schema", "DataFetchingEnvironment");
    private static final ClassName SELECTION_SET     = ClassName.get("graphql.schema", "DataFetchingFieldSelectionSet");

    public TypeSpec generate(String typeName) {
        String prefix = decapitalize(typeName);
        return TypeSpec.classBuilder(typeName + "Fields")
            .addModifiers(Modifier.PUBLIC)
            .addMethod(buildSelectManyMethod(prefix))
            .addMethod(buildSelectOneMethod(prefix))
            .addMethod(buildSubselectManyMethod(prefix))
            .addMethod(buildSubselectOneMethod(prefix))
            .build();
    }

    private MethodSpec buildSelectManyMethod(String prefix) {
        return MethodSpec.methodBuilder(prefix + "SelectMany")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(ParameterizedTypeName.get(RESULT, RECORD))
            .addParameter(ENV, "env")
            .addParameter(CONDITION, "condition")
            .addParameter(sortFieldList(), "orderBy")
            .addStatement("throw new $T()", UnsupportedOperationException.class)
            .build();
    }

    private MethodSpec buildSelectOneMethod(String prefix) {
        return MethodSpec.methodBuilder(prefix + "SelectOne")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(RECORD)
            .addParameter(ENV, "env")
            .addParameter(CONDITION, "condition")
            .addStatement("throw new $T()", UnsupportedOperationException.class)
            .build();
    }

    private MethodSpec buildSubselectManyMethod(String prefix) {
        return MethodSpec.methodBuilder(prefix + "SubselectMany")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(ParameterizedTypeName.get(FIELD, ParameterizedTypeName.get(RESULT, RECORD)))
            .addParameter(SELECTION_SET, "sel")
            .addParameter(CONDITION, "condition")
            .addParameter(sortFieldList(), "orderBy")
            .addStatement("throw new $T()", UnsupportedOperationException.class)
            .build();
    }

    private MethodSpec buildSubselectOneMethod(String prefix) {
        return MethodSpec.methodBuilder(prefix + "SubselectOne")
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

    private static String decapitalize(String s) {
        return Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }
}
