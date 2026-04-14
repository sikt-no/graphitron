package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.TypeSpec;

import javax.lang.model.element.Modifier;
import java.util.List;

/**
 * Generates {@code GraphitronWiring.java} in {@code <outputPackage>.rewrite}.
 *
 * <p>The generated class contains a single {@code build()} method that returns a
 * {@code RuntimeWiring.Builder} with all generated {@code *Fields.wiring()} calls registered.
 * The caller can add further type resolvers (e.g. for custom scalars, unions) before calling
 * {@code .build()} on the returned builder.
 *
 * <p>This is the glue that connects the per-type wiring methods into a single
 * {@code RuntimeWiring} usable by graphql-java's {@code GraphQL.newGraphQL(schema, wiring)}.
 */
public class GraphitronWiringClassGenerator {

    private static final ClassName RUNTIME_WIRING = ClassName.get("graphql.schema.idl", "RuntimeWiring");

    /**
     * Generates the {@code GraphitronWiring} class.
     *
     * @param fieldsClassNames the simple class names of all generated {@code *Fields} classes
     *                         (e.g. {@code ["CustomerFields", "FilmFields", "QueryFields"]}),
     *                         in the order they should appear in the wiring
     */
    public static TypeSpec generate(List<String> fieldsClassNames) {
        var typesPackage = GeneratorConfig.outputPackage() + ".rewrite.types";
        var builderType = ClassName.get("graphql.schema.idl", "RuntimeWiring", "Builder");

        var body = CodeBlock.builder()
            .add("return $T.newRuntimeWiring()", RUNTIME_WIRING);

        if (fieldsClassNames.isEmpty()) {
            body.add(";\n");
        } else {
            body.indent();
            for (var className : fieldsClassNames) {
                var fieldsClass = ClassName.get(typesPackage, className);
                body.add("\n.type($T.wiring())", fieldsClass);
            }
            body.add(";\n");
            body.unindent();
        }

        var buildMethod = MethodSpec.methodBuilder("build")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(builderType)
            .addCode(body.build())
            .build();

        return TypeSpec.classBuilder("GraphitronWiring")
            .addModifiers(Modifier.PUBLIC)
            .addMethod(buildMethod)
            .build();
    }
}
