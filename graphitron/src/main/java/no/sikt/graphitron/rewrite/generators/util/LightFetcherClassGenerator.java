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
 * Generates the {@code LightFetcher<T>} utility class, emitted once per code-generation run
 * alongside other rewrite output.
 *
 * <p>The generated class implements {@link graphql.schema.LightDataFetcher} and wraps a named
 * source-read method so the env-skipping fast path is preserved while the read itself remains a
 * findable {@code <Type>Fetchers} symbol:
 * <pre>{@code
 * .dataFetcher("title", new LightFetcher<>(FilmFetchers::title))
 * }</pre>
 *
 * <p>It holds a {@code Read} function (a source-only read) rather than a jOOQ column, so any
 * per-field read — a column projection, an aliased subquery read, a NodeId encode, a class-backed
 * accessor — can ride the light path by being lifted into a named {@code <Type>Fetchers} method
 * that this class wraps.
 *
 * <p>Generated as a source file rather than shipped as a library dependency so that consuming
 * projects have no runtime dependency on Graphitron itself.
 */
public class LightFetcherClassGenerator {

    public static final String CLASS_NAME = "LightFetcher";

    private static final ClassName LIGHT_DATA_FETCHER  = ClassName.get("graphql.schema", "LightDataFetcher");
    private static final ClassName ENV                 = ClassName.get("graphql.schema", "DataFetchingEnvironment");
    private static final ClassName FIELD_DEFINITION    = ClassName.get("graphql.schema", "GraphQLFieldDefinition");
    private static final ClassName SUPPLIER            = ClassName.get("java.util.function", "Supplier");

    public static List<TypeSpec> generate(String outputPackage) {
        var T = TypeVariableName.get("T");
        var readClass = ClassName.get(outputPackage + ".util", CLASS_NAME, "Read");
        var readOfT = ParameterizedTypeName.get(readClass, T);

        // Nested SAM: a source-only read. Named Read<R> (rather than reusing Function<Object, R>)
        // so the generated method-reference target documents intent at the registration site.
        var R = TypeVariableName.get("R");
        var readInterface = TypeSpec.interfaceBuilder("Read")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addAnnotation(FunctionalInterface.class)
            .addTypeVariable(R)
            .addMethod(MethodSpec.methodBuilder("apply")
                .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                .returns(R)
                .addParameter(Object.class, "source")
                .build())
            .build();

        var readField = FieldSpec.builder(readOfT, "read", Modifier.PRIVATE, Modifier.FINAL)
            .build();

        var constructor = MethodSpec.constructorBuilder()
            .addModifiers(Modifier.PUBLIC)
            .addParameter(readOfT, "read")
            .addStatement("this.read = read")
            .build();

        var getEnv = MethodSpec.methodBuilder("get")
            .addAnnotation(Override.class)
            .addModifiers(Modifier.PUBLIC)
            .returns(T)
            .addParameter(ENV, "env")
            .addException(Exception.class)
            .addStatement("return read.apply(env.getSource())")
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
            .addStatement("return read.apply(source)")
            .build();

        var spec = TypeSpec.classBuilder(CLASS_NAME)
            .addModifiers(Modifier.PUBLIC)
            .addTypeVariable(T)
            .addSuperinterface(ParameterizedTypeName.get(LIGHT_DATA_FETCHER, T))
            .addType(readInterface)
            .addField(readField)
            .addMethod(constructor)
            .addMethod(getEnv)
            .addMethod(getLight)
            .build();

        return List.of(spec);
    }
}
