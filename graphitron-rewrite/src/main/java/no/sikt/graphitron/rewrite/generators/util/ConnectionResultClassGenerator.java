package no.sikt.graphitron.rewrite.generators.util;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.FieldSpec;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.javapoet.WildcardTypeName;

import javax.lang.model.element.Modifier;
import java.util.List;

/**
 * Generates the {@code ConnectionResult} carrier class, emitted once per code-generation run.
 *
 * <p>A connection field fetcher returns a {@code ConnectionResult} wrapping the raw
 * {@code Result<Record>} together with pagination context (page size, cursor, resolved ORDER BY
 * columns). This object becomes {@code env.getSource()} for all Connection-level resolvers
 * ({@code edges}, {@code nodes}, {@code pageInfo}).
 *
 * <p>Generated as a source file rather than shipped as a library dependency so that consuming
 * projects have no runtime dependency on Graphitron itself.
 */
public class ConnectionResultClassGenerator {

    public static final String CLASS_NAME = "ConnectionResult";

    private static final ClassName RESULT       = ClassName.get("org.jooq", "Result");
    private static final ClassName RECORD       = ClassName.get("org.jooq", "Record");
    private static final ClassName JOOQ_FIELD   = ClassName.get("org.jooq", "Field");
    private static final ClassName LIST         = ClassName.get(List.class);

    public static List<TypeSpec> generate() {
        var resultOfRecord = ParameterizedTypeName.get(RESULT, RECORD);
        var fieldWildcard = ParameterizedTypeName.get(JOOQ_FIELD, WildcardTypeName.subtypeOf(Object.class));
        var listOfField = ParameterizedTypeName.get(LIST, fieldWildcard);

        // Fields
        var resultField = FieldSpec.builder(resultOfRecord, "result", Modifier.PRIVATE, Modifier.FINAL).build();
        var pageSizeField = FieldSpec.builder(int.class, "pageSize", Modifier.PRIVATE, Modifier.FINAL).build();
        var cursorField = FieldSpec.builder(String.class, "cursor", Modifier.PRIVATE, Modifier.FINAL).build();
        var orderByColumnsField = FieldSpec.builder(listOfField, "orderByColumns", Modifier.PRIVATE, Modifier.FINAL).build();

        // Constructor
        var constructor = MethodSpec.constructorBuilder()
            .addModifiers(Modifier.PUBLIC)
            .addParameter(resultOfRecord, "result")
            .addParameter(int.class, "pageSize")
            .addParameter(String.class, "cursor")
            .addParameter(listOfField, "orderByColumns")
            .addStatement("this.result = result")
            .addStatement("this.pageSize = pageSize")
            .addStatement("this.cursor = cursor")
            .addStatement("this.orderByColumns = orderByColumns")
            .build();

        // Accessors
        var getResult = MethodSpec.methodBuilder("result")
            .addModifiers(Modifier.PUBLIC)
            .returns(resultOfRecord)
            .addStatement("return result")
            .build();

        var getPageSize = MethodSpec.methodBuilder("pageSize")
            .addModifiers(Modifier.PUBLIC)
            .returns(int.class)
            .addStatement("return pageSize")
            .build();

        var getCursor = MethodSpec.methodBuilder("cursor")
            .addModifiers(Modifier.PUBLIC)
            .returns(String.class)
            .addStatement("return cursor")
            .build();

        var getOrderByColumns = MethodSpec.methodBuilder("orderByColumns")
            .addModifiers(Modifier.PUBLIC)
            .returns(listOfField)
            .addStatement("return orderByColumns")
            .build();

        // trimmedResult() — trims to pageSize (the over-fetch row is excluded)
        var listOfRecord = ParameterizedTypeName.get(LIST, RECORD);
        var trimmedResult = MethodSpec.methodBuilder("trimmedResult")
            .addModifiers(Modifier.PUBLIC)
            .returns(listOfRecord)
            .addStatement("if (result.size() <= pageSize) return result")
            .addStatement("return result.subList(0, pageSize)")
            .build();

        // hasNextPage() — true when the result has more rows than the page size
        var hasNextPage = MethodSpec.methodBuilder("hasNextPage")
            .addModifiers(Modifier.PUBLIC)
            .returns(boolean.class)
            .addStatement("return result.size() > pageSize")
            .build();

        // hasPreviousPage() — true when cursor (after) was non-null (pragmatic shortcut)
        var hasPreviousPage = MethodSpec.methodBuilder("hasPreviousPage")
            .addModifiers(Modifier.PUBLIC)
            .returns(boolean.class)
            .addStatement("return cursor != null")
            .build();

        var spec = TypeSpec.classBuilder(CLASS_NAME)
            .addModifiers(Modifier.PUBLIC)
            .addField(resultField)
            .addField(pageSizeField)
            .addField(cursorField)
            .addField(orderByColumnsField)
            .addMethod(constructor)
            .addMethod(getResult)
            .addMethod(getPageSize)
            .addMethod(getCursor)
            .addMethod(getOrderByColumns)
            .addMethod(trimmedResult)
            .addMethod(hasNextPage)
            .addMethod(hasPreviousPage)
            .build();

        return List.of(spec);
    }
}
