package no.sikt.graphitron.rewrite.generators.util;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.FieldSpec;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.javapoet.TypeVariableName;

import javax.lang.model.element.Modifier;
import java.util.List;

/**
 * Generates the {@code ColumnFetcher<T>} utility class, emitted once per code-generation run
 * alongside other rewrite output.
 *
 * <p>The generated class implements {@link graphql.schema.LightDataFetcher} and is used in place
 * of per-field fetcher methods for column fields:
 * <pre>{@code
 * .dataFetcher("title", new ColumnFetcher<>(Tables.FILM.TITLE))
 * }</pre>
 *
 * <p>Generated as a source file rather than shipped as a library dependency so that consuming
 * projects have no runtime dependency on Graphitron itself.
 */
public class ColumnFetcherClassGenerator {

    public static final String CLASS_NAME = "ColumnFetcher";

    private static final ClassName LIGHT_DATA_FETCHER  = ClassName.get("graphql.schema", "LightDataFetcher");
    private static final ClassName ENV                 = ClassName.get("graphql.schema", "DataFetchingEnvironment");
    private static final ClassName FIELD_DEFINITION    = ClassName.get("graphql.schema", "GraphQLFieldDefinition");
    private static final ClassName SUPPLIER            = ClassName.get("java.util.function", "Supplier");
    private static final ClassName JOOQ_FIELD          = ClassName.get("org.jooq", "Field");
    private static final ClassName RECORD              = ClassName.get("org.jooq", "Record");

    public static List<TypeSpec> generate() {
        var T = TypeVariableName.get("T");
        var fieldType = ParameterizedTypeName.get(JOOQ_FIELD, T);

        var columnField = FieldSpec.builder(fieldType, "column", Modifier.PRIVATE, Modifier.FINAL)
            .build();

        var constructor = MethodSpec.constructorBuilder()
            .addModifiers(Modifier.PUBLIC)
            .addParameter(fieldType, "column")
            .addStatement("this.column = column")
            .build();

        var getEnv = MethodSpec.methodBuilder("get")
            .addAnnotation(Override.class)
            .addModifiers(Modifier.PUBLIC)
            .returns(T)
            .addParameter(ENV, "env")
            .addException(Exception.class)
            .addStatement("return (($T) env.getSource()).get(column)", RECORD)
            .build();

        var supplierOfEnv = ParameterizedTypeName.get(SUPPLIER, ENV);
        var getLight = MethodSpec.methodBuilder("get")
            .addAnnotation(Override.class)
            .addModifiers(Modifier.PUBLIC)
            .returns(T)
            .addParameter(FIELD_DEFINITION, "fieldDef")
            .addParameter(Object.class, "source")
            .addParameter(supplierOfEnv, "dfeSupplier")
            .addException(Exception.class)
            .addStatement("return (($T) source).get(column)", RECORD)
            .build();

        var spec = TypeSpec.classBuilder(CLASS_NAME)
            .addModifiers(Modifier.PUBLIC)
            .addTypeVariable(T)
            .addSuperinterface(ParameterizedTypeName.get(LIGHT_DATA_FETCHER, T))
            .addField(columnField)
            .addMethod(constructor)
            .addMethod(getEnv)
            .addMethod(getLight)
            .build();

        return List.of(spec);
    }
}
