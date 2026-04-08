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
 * <p>Each class contains two scope-establishing stub methods:
 * <ul>
 *   <li>{@code <typeName>Select} — starts a new SQL statement (root queries, DataLoaders)</li>
 *   <li>{@code <typeName>Nested} — contributes as a multiset subquery (inline fields)</li>
 * </ul>
 *
 * <p>Both stubs throw {@link UnsupportedOperationException} until their bodies are filled in by
 * subsequent deliverables.
 */
public class FieldsCodeGenerator {

    private static final ClassName DSL_CONTEXT       = ClassName.get("org.jooq", "DSLContext");
    private static final ClassName SELECT_FINAL_STEP = ClassName.get("org.jooq", "SelectFinalStep");
    private static final ClassName RECORD            = ClassName.get("org.jooq", "Record");
    private static final ClassName FIELD             = ClassName.get("org.jooq", "Field");
    private static final ClassName RESULT            = ClassName.get("org.jooq", "Result");
    private static final ClassName CONDITION         = ClassName.get("org.jooq", "Condition");
    private static final ClassName SORT_FIELD        = ClassName.get("org.jooq", "SortField");
    private static final ClassName LIST              = ClassName.get(List.class);
    private static final ClassName SELECTION_SET     = ClassName.get("graphql.schema", "DataFetchingFieldSelectionSet");

    public TypeSpec generate(String typeName) {
        String prefix = decapitalize(typeName);
        return TypeSpec.classBuilder(typeName + "Fields")
            .addModifiers(Modifier.PUBLIC)
            .addMethod(buildSelectMethod(prefix))
            .addMethod(buildNestedMethod(prefix))
            .build();
    }

    private MethodSpec buildSelectMethod(String prefix) {
        return MethodSpec.methodBuilder(prefix + "Select")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(ParameterizedTypeName.get(SELECT_FINAL_STEP, RECORD))
            .addParameter(DSL_CONTEXT, "ctx")
            .addParameter(SELECTION_SET, "sel")
            .addParameter(CONDITION, "condition")
            .addParameter(sortFieldList(), "orderBy")
            .addStatement("throw new $T()", UnsupportedOperationException.class)
            .build();
    }

    private MethodSpec buildNestedMethod(String prefix) {
        return MethodSpec.methodBuilder(prefix + "Nested")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(ParameterizedTypeName.get(FIELD, ParameterizedTypeName.get(RESULT, RECORD)))
            .addParameter(SELECTION_SET, "sel")
            .addParameter(CONDITION, "condition")
            .addParameter(sortFieldList(), "orderBy")
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
