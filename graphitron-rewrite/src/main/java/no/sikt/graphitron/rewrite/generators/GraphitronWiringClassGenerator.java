package no.sikt.graphitron.rewrite.generators;


import no.sikt.graphitron.rewrite.RewriteConfig;
import no.sikt.graphitron.rewrite.generators.util.ConnectionHelperClassGenerator;
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
 * {@code RuntimeWiring.Builder} with all generated {@code *Fetchers.wiring()} calls registered.
 * The caller can add further type resolvers (e.g. for custom scalars, unions) before calling
 * {@code .build()} on the returned builder.
 *
 * <p>This is the glue that connects the per-type wiring methods into a single
 * {@code RuntimeWiring} usable by graphql-java's {@code GraphQL.newGraphQL(schema, wiring)}.
 */
public class GraphitronWiringClassGenerator {

    private static final ClassName RUNTIME_WIRING = ClassName.get("graphql.schema.idl", "RuntimeWiring");

    /**
     * Connection wiring info: the generated Connection and Edge type names for one connection field.
     *
     * @param connectionTypeName e.g. "QueryFilmsConnection"
     * @param edgeTypeName       e.g. "QueryFilmsEdge"
     */
    public record ConnectionWiring(String connectionTypeName, String edgeTypeName) {}

    /**
     * Generates the {@code GraphitronWiring} class.
     *
     * @param fetcherClassNames  the simple class names of all generated {@code *Fetchers} classes
     * @param connectionWirings  connection type wiring entries for Connection/Edge types
     */
    public static TypeSpec generate(List<String> fetcherClassNames, List<ConnectionWiring> connectionWirings) {
        var typesPackage = RewriteConfig.outputPackage() + ".rewrite.types";
        var rewritePackage = RewriteConfig.outputPackage() + ".rewrite";
        var builderType = ClassName.get("graphql.schema.idl", "RuntimeWiring", "Builder");

        var body = CodeBlock.builder()
            .add("return $T.newRuntimeWiring()", RUNTIME_WIRING);

        boolean hasEntries = !fetcherClassNames.isEmpty() || !connectionWirings.isEmpty();

        if (!hasEntries) {
            body.add(";\n");
        } else {
            body.indent();
            for (var className : fetcherClassNames) {
                var fetcherClass = ClassName.get(typesPackage, className);
                body.add("\n.type($T.wiring())", fetcherClass);
            }

            // Connection type wiring: edges, nodes, pageInfo
            var connectionHelperClass = ClassName.get(rewritePackage,
                ConnectionHelperClassGenerator.CLASS_NAME);
            var TYPE_WIRING = ClassName.get("graphql.schema.idl", "TypeRuntimeWiring");

            for (var cw : connectionWirings) {
                body.add("\n.type($T.newTypeWiring($S)\n", TYPE_WIRING, cw.connectionTypeName());
                body.indent();
                body.add(".dataFetcher(\"edges\", $T::edges)\n", connectionHelperClass);
                body.add(".dataFetcher(\"nodes\", $T::nodes)\n", connectionHelperClass);
                body.add(".dataFetcher(\"pageInfo\", $T::pageInfo))", connectionHelperClass);
                body.unindent();

                body.add("\n.type($T.newTypeWiring($S)\n", TYPE_WIRING, cw.edgeTypeName());
                body.indent();
                body.add(".dataFetcher(\"node\", $T::edgeNode)\n", connectionHelperClass);
                body.add(".dataFetcher(\"cursor\", $T::edgeCursor))", connectionHelperClass);
                body.unindent();
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

    /** Backward-compatible overload for callers that don't have connection wiring info. */
    public static TypeSpec generate(List<String> fetcherClassNames) {
        return generate(fetcherClassNames, List.of());
    }
}
