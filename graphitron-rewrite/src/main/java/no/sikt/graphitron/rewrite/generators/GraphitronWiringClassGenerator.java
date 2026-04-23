package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.rewrite.RewriteConfig;
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
 * {@code RuntimeWiring.Builder} with all generated {@code *Wiring.wiring()} calls registered —
 * one per GraphQL object type, each produced by {@link WiringClassGenerator}.
 *
 * <p>The caller can add further type resolvers (e.g. for custom scalars, unions) before calling
 * {@code .build()} on the returned builder.
 */
public class GraphitronWiringClassGenerator {

    private static final ClassName RUNTIME_WIRING = ClassName.get("graphql.schema.idl", "RuntimeWiring");

    /**
     * Generates the {@code GraphitronWiring} aggregator class.
     *
     * @param wiringClassNames the simple class names of all generated {@code *Wiring} classes,
     *                         sorted alphabetically
     */
    public static TypeSpec generate(List<String> wiringClassNames) {
        String wiringPackage = RewriteConfig.outputPackage() + ".rewrite.wiring";
        ClassName builderType = ClassName.get("graphql.schema.idl", "RuntimeWiring", "Builder");

        CodeBlock.Builder body = CodeBlock.builder()
            .add("return $T.newRuntimeWiring()", RUNTIME_WIRING);

        if (wiringClassNames.isEmpty()) {
            body.add(";\n");
        } else {
            body.indent();
            for (String name : wiringClassNames) {
                body.add("\n.type($T.wiring())", ClassName.get(wiringPackage, name));
            }
            body.add(";\n").unindent();
        }

        MethodSpec buildMethod = MethodSpec.methodBuilder("build")
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
