package no.sikt.graphitron.rewrite.generators.schema;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.generators.util.GraphitronContextInterfaceGenerator;
import no.sikt.graphitron.rewrite.model.ResolvedContextArg;

import javax.lang.model.element.Modifier;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Generates the {@code Graphitron} facade class in {@code <outputPackage>} (the root output package).
 * The public static {@code buildSchema(Consumer<GraphQLSchema.Builder> customizer)} method
 * delegates to {@link GraphitronSchemaClassGenerator}'s emitted {@code GraphitronSchema.build(...)};
 * the assembler is free to evolve internally without changing the facade. Customizer contract
 * (additive-only; must not call {@code .query()}, {@code .mutation()},
 * {@code .subscription()}, {@code .clearDirectives()}, or the replace overload
 * {@code .codeRegistry(GraphQLCodeRegistry)}) lives on the emitted method's own javadoc.
 *
 * <p>The facade also exposes one schema-driven {@code newExecutionInput} factory whose parameter
 * list reflects the schema's declared {@code contextArguments}: a {@code DSLContext defaultDsl}
 * first, then one typed parameter per contextArgument name (alphabetical), read from the cached
 * {@link GraphitronSchema#contextArguments()} classification.
 *
 * <p>R190 collapsed the legacy two-overload shape ({@code (GraphitronContext)} +
 * {@code (DSLContext)}) into this single typed entry point. The sealed
 * {@code GraphitronContext} now permits only the generated {@code GraphitronContextImpl}
 * singleton; the factory IS the per-request wiring point.
 */
public final class GraphitronFacadeGenerator {

    public static final String CLASS_NAME = "Graphitron";

    private GraphitronFacadeGenerator() {}

    public static List<TypeSpec> generate(GraphitronSchema schema, String outputPackage, boolean federationLink) {
        String schemaPackage = outputPackage + ".schema";
        var graphQLSchema = ClassName.get("graphql.schema", "GraphQLSchema");
        var schemaBuilder = ClassName.get("graphql.schema", "GraphQLSchema", "Builder");
        var customizerType = ParameterizedTypeName.get(ClassName.get(Consumer.class), schemaBuilder);
        var graphitronSchema = ClassName.get(schemaPackage, GraphitronSchemaClassGenerator.CLASS_NAME);
        var graphitronContext = ClassName.get(schemaPackage, GraphitronContextInterfaceGenerator.CLASS_NAME);
        var graphitronContextImpl = graphitronContext.nestedClass(GraphitronContextInterfaceGenerator.IMPL_CLASS_NAME);
        var executionInput = ClassName.get("graphql", "ExecutionInput");
        var executionInputBuilder = ClassName.get("graphql", "ExecutionInput", "Builder");
        var graphQL = ClassName.get("graphql", "GraphQL");
        var graphQLBuilder = ClassName.get("graphql", "GraphQL", "Builder");
        var dataLoaderRegistry = ClassName.get("org.dataloader", "DataLoaderRegistry");
        var dslContext = ClassName.get("org.jooq", "DSLContext");

        var buildSchema = MethodSpec.methodBuilder("buildSchema")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(graphQLSchema)
            .addParameter(customizerType, "customizer")
            .addStatement("return $T.build(customizer)", graphitronSchema)
            .addJavadoc(buildSchemaJavadoc(federationLink))
            .build();

        // TreeMap iteration order is alphabetical by key: same order generated fetchers will
        // resolve them in, same order the user docs talk about them in, deterministic across runs.
        // Read off the schema's cached classification rather than re-running the classifier:
        // GraphitronSchemaValidator.validateContextArgumentTypeAgreement reads the same field,
        // so both consumers see one producer.
        List<ResolvedContextArg> contextArgs = schema.contextArguments().resolved().values().stream().toList();

        var newExecutionInput = buildNewExecutionInput(
            graphitronContext, graphitronContextImpl, executionInput, executionInputBuilder,
            dataLoaderRegistry, dslContext, contextArgs);

        var newGraphQL = MethodSpec.methodBuilder("newGraphQL")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(graphQLBuilder)
            .addStatement("return $T.newGraphQL(buildSchema(customizer -> {}))", graphQL)
            .addJavadoc(newGraphQLJavadoc())
            .build();

        var classBuilder = TypeSpec.classBuilder(CLASS_NAME)
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addJavadoc(classJavadoc())
            .addMethod(buildSchema)
            .addMethod(newExecutionInput)
            .addMethod(newGraphQL);

        if (federationLink) {
            var SCHEMA_TRANSFORMER = ClassName.get("com.apollographql.federation.graphqljava", "SchemaTransformer");
            var fedCustomizerType = ParameterizedTypeName.get(ClassName.get(Consumer.class), SCHEMA_TRANSFORMER);

            var buildSchemaFed = MethodSpec.methodBuilder("buildSchema")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(graphQLSchema)
                .addParameter(customizerType, "schemaCustomizer")
                .addParameter(fedCustomizerType, "federationCustomizer")
                .addStatement("return $T.build(schemaCustomizer, federationCustomizer)", graphitronSchema)
                .addJavadoc("Builds the federation-wrapped schema, optionally customizing the\n"
                    + "{@link $T} builder after Graphitron's defaults are attached.\n"
                    + "The {@code federationCustomizer} may call {@code .fetchEntities(...)}\n"
                    + "to override the default entity fetcher. Do not call {@code .build()}\n"
                    + "from the customizer.\n"
                    + "@param schemaCustomizer hook applied to the base schema builder\n"
                    + "@param federationCustomizer hook applied to the federation builder\n"
                    + "@return the fully wired federation {@link graphql.schema.GraphQLSchema}\n",
                    SCHEMA_TRANSFORMER)
                .build();
            classBuilder.addMethod(buildSchemaFed);
        }

        return List.of(classBuilder.build());
    }

    /** Convenience overload for non-federation usage. */
    public static List<TypeSpec> generate(GraphitronSchema schema, String outputPackage) {
        return generate(schema, outputPackage, false);
    }

    private static MethodSpec buildNewExecutionInput(
            ClassName graphitronContext, ClassName graphitronContextImpl,
            ClassName executionInput, ClassName executionInputBuilder,
            ClassName dataLoaderRegistry, ClassName dslContext,
            List<ResolvedContextArg> contextArgs) {
        var method = MethodSpec.methodBuilder("newExecutionInput")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(executionInputBuilder)
            .addParameter(dslContext, "defaultDsl");
        for (ResolvedContextArg arg : contextArgs) {
            method.addParameter(arg.javaType(), arg.name());
        }
        method.addStatement("$T.requireNonNull(defaultDsl, $S)", Objects.class, "defaultDsl");
        for (ResolvedContextArg arg : contextArgs) {
            method.addStatement("$T.requireNonNull($L, $S)", Objects.class, arg.name(), arg.name());
        }

        // Build the graphQLContext lambda body: stash DSLContext under DSLContext.class, each
        // contextArgument under its string name, and the singleton GraphitronContextImpl under
        // GraphitronContext.class. The downstream `graphitronContext(env)` helper retrieves the
        // singleton by typed key; per-request values flow through env.getGraphQlContext() reads
        // inside the singleton's default methods.
        method.addCode("return $T.newExecutionInput()\n", executionInput);
        method.addCode("    .graphQLContext(b -> {\n");
        method.addCode("        b.put($T.class, defaultDsl);\n", dslContext);
        for (ResolvedContextArg arg : contextArgs) {
            method.addCode("        b.put($S, $L);\n", arg.name(), arg.name());
        }
        method.addCode("        b.put($T.class, $T.INSTANCE);\n", graphitronContext, graphitronContextImpl);
        method.addCode("    })\n");
        method.addCode("    .dataLoaderRegistry(new $T());\n", dataLoaderRegistry);
        method.addJavadoc(newExecutionInputJavadoc(contextArgs));
        return method.build();
    }

    private static String newGraphQLJavadoc() {
        return "Builds a {@link graphql.GraphQL.Builder} for the zero-configuration default case,\n"
            + "equivalent to {@code GraphQL.newGraphQL(buildSchema(b -> {}))}. Chain {@code .build()}\n"
            + "to obtain the engine: {@code var graphql = Graphitron.newGraphQL().build();}.\n"
            + "\n"
            + "<p>Returns a builder rather than a built {@link graphql.GraphQL} so callers can still\n"
            + "attach instrumentation or execution strategies before {@code .build()}. Consumers that\n"
            + "need to customize the schema (extra scalars, additional types, custom directives, or a\n"
            + "federation entity fetcher) should call {@link #buildSchema(java.util.function.Consumer)}\n"
            + "directly and wrap it with {@code GraphQL.newGraphQL(...)} themselves; this convenience\n"
            + "covers only the no-extra-wiring default.\n"
            + "\n"
            + "<p>Per-request runtime values (DSLContext, contextArguments) still travel via\n"
            + "{@code Graphitron.newExecutionInput(...)}.\n"
            + "@return a {@link graphql.GraphQL.Builder} over the default-wired schema, ready for {@code .build()}\n";
    }

    private static String classJavadoc() {
        return "Entry point for constructing the Graphitron-built GraphQL schema and shaping\n"
            + "the per-request {@code ExecutionInput} every fetcher reads from.\n"
            + "\n"
            + "<p>Emitted as a hand-written-feeling facade so apps can wire up with one call:\n"
            + "{@code GraphQLSchema schema = Graphitron.buildSchema(b -> {...});}. The delegate\n"
            + "{@link GraphitronSchema} owns the assembly details; this class stays a thin\n"
            + "surface over schema construction and per-request input shaping.\n";
    }

    private static String newExecutionInputJavadoc(List<ResolvedContextArg> contextArgs) {
        var sb = new StringBuilder();
        sb.append("Builds an {@link graphql.ExecutionInput.Builder} pre-wired with the per-request\n");
        sb.append("jOOQ {@code DSLContext} and any declared {@code contextArguments}, plus an empty\n");
        sb.append("{@link org.dataloader.DataLoaderRegistry} (required by graphql-java; generated\n");
        sb.append("fetchers populate it lazily on first lookup).\n");
        sb.append("\n");
        sb.append("<p>The parameter list reflects the schema's declared {@code contextArguments} in\n");
        sb.append("alphabetical order. The body null-checks every parameter and populates the per-request\n");
        sb.append("{@code GraphQLContext}; generated fetchers read each value back via\n");
        sb.append("{@code getContextArgument(env, name)} with an explicit Java cast at the call site.\n");
        sb.append("A missing or wrong-typed contextArgument is a compile error at the typed factory call\n");
        sb.append("site, not a runtime surprise.\n");
        sb.append("\n");
        sb.append("<p>Chain additional {@code .query(...)}, {@code .variables(...)},\n");
        sb.append("{@code .operationName(...)} calls before {@code .build()}. Extra\n");
        sb.append("{@code .graphQLContext(b -> b.put(...))} calls <em>merge</em> with the entries\n");
        sb.append("this factory put. The {@code .dataLoaderRegistry(...)} replace overload swaps out\n");
        sb.append("the factory's empty registry if you need to supply a pre-populated one.\n");
        sb.append("@param defaultDsl the {@code DSLContext} every fetch in this request should use;\n");
        sb.append("must not be {@code null}\n");
        for (ResolvedContextArg arg : contextArgs) {
            sb.append("@param ").append(arg.name())
              .append(" the per-request value for {@code contextArgument \"")
              .append(arg.name()).append("\"}; must not be {@code null}\n");
        }
        sb.append("@return a builder ready for {@code .query(...).build()}\n");
        return sb.toString();
    }

    private static String buildSchemaJavadoc(boolean federationLink) {
        return "Builds the schema with all generator-emitted fetchers attached.\n"
            + (federationLink
               ? "\n<p>This schema was compiled with a federation {@code @link}; the returned\n"
               + "{@link graphql.schema.GraphQLSchema} is wrapped with {@code Federation.transform}\n"
               + "and includes {@code _Service} and {@code _entities}. Do not wrap it again.\n"
               + "Use {@link #buildSchema(java.util.function.Consumer, java.util.function.Consumer)}\n"
               + "to register a custom entity fetcher.\n"
               : "")
            + "\n"
            + "<p>The {@code customizer} receives the underlying {@link graphql.schema.GraphQLSchema.Builder}\n"
            + "for adding scalars, additional types, or custom directives before {@code .build()} is\n"
            + "called. Use additive methods only; do not call {@code .query()}, {@code .mutation()},\n"
            + "{@code .subscription()}, {@code .clearDirectives()}, or the replace overload\n"
            + "{@code .codeRegistry(GraphQLCodeRegistry)}. The {@code .codeRegistry(UnaryOperator)}\n"
            + "overload is fine, and is the supported extension point for adding type resolvers\n"
            + "to user-defined interfaces and unions.\n"
            + "\n"
            + "<p>Per-request runtime values (DSLContext, contextArguments) travel via\n"
            + "{@code Graphitron.newExecutionInput(...)}, which pre-wires the typed context entries\n"
            + "and a fresh {@code DataLoaderRegistry} that generated fetchers populate lazily.\n"
            + "@param customizer hook applied to the schema builder before build;\n"
            + "must not be {@code null}\n"
            + "@return the fully wired {@link graphql.schema.GraphQLSchema}\n";
    }
}
