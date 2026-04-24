package no.sikt.graphitron.rewrite.generators.schema;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeSpec;

import javax.lang.model.element.Modifier;
import java.util.List;
import java.util.function.Consumer;

/**
 * Generates the {@code Graphitron} facade class in {@code <outputPackage>.rewrite.schema}.
 * The single public static {@code buildSchema(Consumer<GraphQLSchema.Builder> customizer)}
 * method delegates to {@link GraphitronSchemaClassGenerator}'s emitted
 * {@code GraphitronSchema.build(...)}; the user-facing surface stays at one method with
 * no hidden state while the assembler is free to evolve internally. Customizer contract
 * (additive-only; must not call {@code .query()}, {@code .mutation()},
 * {@code .subscription()}, {@code .clearDirectives()}, or the replace overload
 * {@code .codeRegistry(GraphQLCodeRegistry)}) lives on the emitted method's own javadoc.
 */
public final class GraphitronFacadeGenerator {

    public static final String CLASS_NAME = "Graphitron";

    private GraphitronFacadeGenerator() {}

    public static List<TypeSpec> generate(String outputPackage) {
        String schemaPackage = outputPackage + ".schema";
        var graphQLSchema = ClassName.get("graphql.schema", "GraphQLSchema");
        var schemaBuilder = ClassName.get("graphql.schema", "GraphQLSchema", "Builder");
        var customizerType = ParameterizedTypeName.get(ClassName.get(Consumer.class), schemaBuilder);

        var buildSchema = MethodSpec.methodBuilder("buildSchema")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(graphQLSchema)
            .addParameter(customizerType, "customizer")
            .addStatement("return $T.build(customizer)",
                ClassName.get(schemaPackage, GraphitronSchemaClassGenerator.CLASS_NAME))
            .addJavadoc(buildSchemaJavadoc())
            .build();

        return List.of(TypeSpec.classBuilder(CLASS_NAME)
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addJavadoc(classJavadoc())
            .addMethod(buildSchema)
            .build());
    }

    private static String classJavadoc() {
        return "Entry point for constructing the Graphitron-built GraphQL schema.\n"
            + "\n"
            + "<p>Emitted as a hand-written-feeling facade so apps can wire up with one call:\n"
            + "{@code GraphQLSchema schema = Graphitron.buildSchema(b -> {...});}. The delegate\n"
            + "{@link GraphitronSchema} owns the assembly details; this class stays minimal so\n"
            + "the public surface is exactly one method.\n";
    }

    private static String buildSchemaJavadoc() {
        return "Builds the schema with all generator-emitted fetchers attached.\n"
            + "\n"
            + "<p>The {@code customizer} receives the underlying {@link graphql.schema.GraphQLSchema.Builder}\n"
            + "for adding scalars, additional types, or custom directives before {@code .build()} is\n"
            + "called. Use additive methods only; do not call {@code .query()}, {@code .mutation()},\n"
            + "{@code .subscription()}, {@code .clearDirectives()}, or the replace overload\n"
            + "{@code .codeRegistry(GraphQLCodeRegistry)}. The {@code .codeRegistry(UnaryOperator)}\n"
            + "overload is fine, and is the supported extension point for adding type resolvers\n"
            + "to user-defined interfaces and unions.\n"
            + "\n"
            + "<p>Per-request runtime values (DSLContext, context arguments, tenant id) travel via\n"
            + "{@code ExecutionInput.newExecutionInput(query).graphQLContext(Map.of(GraphitronContext.class, impl))}.\n"
            + "@param customizer hook applied to the schema builder before build;\n"
            + "must not be {@code null}\n"
            + "@return the fully wired {@link graphql.schema.GraphQLSchema}\n";
    }
}
