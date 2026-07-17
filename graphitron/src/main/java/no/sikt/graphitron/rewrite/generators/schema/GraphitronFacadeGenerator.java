package no.sikt.graphitron.rewrite.generators.schema;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.generators.util.ConnectionRuntimeClassGenerator;
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
 * <p>The facade exposes two schema-driven per-request factories whose parameter lists both reflect
 * the schema's declared {@code contextArguments} (one typed parameter per contextArgument name,
 * alphabetical, read from the cached {@link GraphitronSchema#contextArguments()} classification):
 * <ul>
 *   <li>{@code newExecutionInput(DSLContext defaultDsl, ...)}, the low-opinion escape hatch, where
 *       the caller brings the {@code DSLContext} and owns transactions and identity; and</li>
 *   <li>{@code newOwnedExecutionInput(String claims, ...)}, the owned-connection path, where the
 *       caller brings only the opaque claims and the execution instrumentation pins the connection,
 *       mounts identity, and produces the {@code DSLContext}.</li>
 * </ul>
 * They are distinct names, not overloads, so a caller cannot silently opt out of the owned-path
 * guarantees by passing a {@code DSLContext}; the escape-hatch name is frozen by additive-by-construction.
 *
 * <p>The legacy two-overload shape ({@code (GraphitronContext)} +
 * {@code (DSLContext)}) was collapsed into the typed escape-hatch entry point. The sealed
 * {@code GraphitronContext} now permits only the generated {@code GraphitronContextImpl}
 * singleton; the factories ARE the per-request wiring point.
 */
public final class GraphitronFacadeGenerator {

    public static final String CLASS_NAME = "Graphitron";

    private static final String LOGGER_FIELD = "LOGGER";
    private static final String ESCAPE_HATCH_NOTICE_FIELD = "ESCAPE_HATCH_NOTICE_LOGGED";

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

        var instrumentation = ClassName.get(schemaPackage,
            no.sikt.graphitron.rewrite.generators.util.GraphitronConnectionInstrumentationGenerator.CLASS_NAME);
        String claimsKeyField =
            no.sikt.graphitron.rewrite.generators.util.GraphitronConnectionInstrumentationGenerator.CLAIMS_KEY_FIELD;

        // The escape-hatch factory: the caller brings a DSLContext and owns transactions and
        // identity. Additive-by-construction keeps its name and shape frozen.
        var newExecutionInput = buildExecutionInputFactory(
            "newExecutionInput", dslContext, "defaultDsl",
            CodeBlock.of("b.put($T.class, defaultDsl);", dslContext),
            graphitronContext, graphitronContextImpl, executionInput, executionInputBuilder,
            dataLoaderRegistry, contextArgs, escapeHatchJavadoc(contextArgs));

        // The owned-connection factory: the caller brings only the opaque claims; the execution
        // instrumentation pins the connection, mounts identity, and produces the DSLContext. Distinct
        // name (not an overload of newExecutionInput) so a caller cannot silently opt out of graphitron's
        // guarantees by passing a DSLContext to what they think is the owned path. Writes the claims under
        // the instrumentation's own CLAIMS_KEY constant so the write and read sites cannot drift.
        var newOwnedExecutionInput = buildExecutionInputFactory(
            "newOwnedExecutionInput", ClassName.get(String.class), "claims",
            CodeBlock.of("b.put($T.$L, claims);", instrumentation, claimsKeyField),
            graphitronContext, graphitronContextImpl, executionInput, executionInputBuilder,
            dataLoaderRegistry, contextArgs, ownedExecutionInputJavadoc(contextArgs));

        // The escape-hatch engine attaches no connection-lifecycle instrumentation, so on
        // this path the caller owns transaction demarcation and session identity and graphitron's
        // owned-connection guarantees do not apply. Emit that notice once at wiring time (guarded so it
        // fires once per process even if the engine is rebuilt).
        var newGraphQL = MethodSpec.methodBuilder("newGraphQL")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(graphQLBuilder)
            .beginControlFlow("if ($N.compareAndSet(false, true))", ESCAPE_HATCH_NOTICE_FIELD)
            .addStatement("$N.warn($S)", LOGGER_FIELD,
                "Graphitron.newGraphQL() builds the low-opinion escape-hatch engine: it attaches no "
                    + "connection-lifecycle instrumentation, so you own transaction demarcation and database "
                    + "session identity, and Graphitron's owned-connection guarantees do not apply. For the "
                    + "managed path use Graphitron.runtime(dataSource, dialect).newGraphQL(schema).")
            .endControlFlow()
            .addStatement("return $T.newGraphQL(buildSchema(customizer -> {}))", graphQL)
            .addJavadoc(newGraphQLJavadoc())
            .build();

        var graphitronRuntime = ClassName.get(schemaPackage, ConnectionRuntimeClassGenerator.RUNTIME_CLASS_NAME);
        var dataSource = ClassName.get("javax.sql", "DataSource");
        var sqlDialect = ClassName.get("org.jooq", "SQLDialect");
        var runtime = MethodSpec.methodBuilder("runtime")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(graphitronRuntime)
            .addParameter(dataSource, "dataSource")
            .addParameter(sqlDialect, "dialect")
            .addStatement("return new $T(dataSource, dialect)", graphitronRuntime)
            .addJavadoc(runtimeJavadoc())
            .build();

        var self = ClassName.get(outputPackage, CLASS_NAME);
        var logger = ClassName.get("org.slf4j", "Logger");
        var loggerFactory = ClassName.get("org.slf4j", "LoggerFactory");
        var atomicBoolean = ClassName.get("java.util.concurrent.atomic", "AtomicBoolean");
        var loggerField = no.sikt.graphitron.javapoet.FieldSpec.builder(
                logger, LOGGER_FIELD, Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
            .initializer("$T.getLogger($T.class)", loggerFactory, self)
            .build();
        var escapeHatchNoticeField = no.sikt.graphitron.javapoet.FieldSpec.builder(
                atomicBoolean, ESCAPE_HATCH_NOTICE_FIELD, Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
            .initializer("new $T(false)", atomicBoolean)
            .addJavadoc("Guards the escape-hatch caller-owns-everything notice so {@link #newGraphQL()}\n"
                + "logs it once per process, not once per call.\n")
            .build();

        var classBuilder = TypeSpec.classBuilder(CLASS_NAME)
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addJavadoc(classJavadoc())
            .addField(loggerField)
            .addField(escapeHatchNoticeField)
            .addMethod(buildSchema)
            .addMethod(newExecutionInput)
            .addMethod(newOwnedExecutionInput)
            .addMethod(newGraphQL)
            .addMethod(runtime);

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

    /**
     * Emits one {@code ExecutionInput.Builder} factory. The two factories, the escape-hatch
     * {@code newExecutionInput(DSLContext, ...)} and the owned-path {@code newOwnedExecutionInput(String
     * claims, ...)}, differ only in their leading parameter and the single {@code graphQLContext} entry
     * it produces ({@code firstPut}); everything schema-shaped (the alphabetical contextArgument
     * parameters, their null-checks, the {@code GraphitronContext} singleton, the empty
     * {@code DataLoaderRegistry}) is identical and single-sourced here so the two cannot drift.
     */
    private static MethodSpec buildExecutionInputFactory(
            String methodName, ClassName firstParamType, String firstParamName, CodeBlock firstPut,
            ClassName graphitronContext, ClassName graphitronContextImpl,
            ClassName executionInput, ClassName executionInputBuilder,
            ClassName dataLoaderRegistry, List<ResolvedContextArg> contextArgs, String javadoc) {
        var method = MethodSpec.methodBuilder(methodName)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(executionInputBuilder)
            .addParameter(firstParamType, firstParamName);
        for (ResolvedContextArg arg : contextArgs) {
            method.addParameter(arg.javaType(), arg.name());
        }
        method.addStatement("$T.requireNonNull($L, $S)", Objects.class, firstParamName, firstParamName);
        for (ResolvedContextArg arg : contextArgs) {
            method.addStatement("$T.requireNonNull($L, $S)", Objects.class, arg.name(), arg.name());
        }

        // Build the graphQLContext lambda body: the factory-specific first entry (a DSLContext for the
        // escape hatch, the claims payload for the owned path), then each contextArgument under its string
        // name, then the singleton GraphitronContextImpl under GraphitronContext.class. The downstream
        // `graphitronContext(env)` helper retrieves the singleton by typed key; per-request values flow
        // through env.getGraphQlContext() reads inside the singleton's default methods.
        method.addCode("return $T.newExecutionInput()\n", executionInput);
        method.addCode("    .graphQLContext(b -> {\n");
        method.addCode("        $L\n", firstPut);
        for (ResolvedContextArg arg : contextArgs) {
            method.addCode("        b.put($S, $L);\n", arg.name(), arg.name());
        }
        method.addCode("        b.put($T.class, $T.INSTANCE);\n", graphitronContext, graphitronContextImpl);
        method.addCode("    })\n");
        method.addCode("    .dataLoaderRegistry(new $T());\n", dataLoaderRegistry);
        method.addJavadoc(javadoc);
        return method.build();
    }

    private static String runtimeJavadoc() {
        return "Builds the application-scoped {@code GraphitronRuntime} that owns the connection\n"
            + "lifecycle: it pins one connection per operation, mounts and unmounts per-request identity\n"
            + "through the database session hooks, and demarcates operation-typed transactions. Built once\n"
            + "at wiring time over a consumer-owned pooled {@code DataSource} and its jOOQ dialect:\n"
            + "{@code var runtime = Graphitron.runtime(dataSource, SQLDialect.POSTGRES);}.\n"
            + "\n"
            + "<p>This is the opinionated path: per request you call\n"
            + "{@code Graphitron.newOwnedExecutionInput(claims, ...)} and graphitron acquires the\n"
            + "connection, runs the connect hook, and releases at completion. The lower-opinion escape\n"
            + "hatch is the static {@code Graphitron.newExecutionInput(dsl, ...)} form, where the caller\n"
            + "owns the {@code DSLContext}, transaction demarcation, and identity state.\n"
            + "@param dataSource the consumer's pooled {@code DataSource} (the consumer owns pool\n"
            + "creation and tuning); must not be {@code null}\n"
            + "@param dialect the jOOQ {@code SQLDialect} for this database; must not be {@code null}\n"
            + "@return the application-scoped runtime, to be held for the app's lifetime\n";
    }

    private static String newGraphQLJavadoc() {
        return "Builds a {@link graphql.GraphQL.Builder} for the zero-configuration default case,\n"
            + "equivalent to {@code GraphQL.newGraphQL(buildSchema(b -> {}))}. Chain {@code .build()}\n"
            + "to obtain the engine: {@code var graphql = Graphitron.newGraphQL().build();}.\n"
            + "\n"
            + "<p>This is the low-opinion escape-hatch engine: it attaches no connection-lifecycle\n"
            + "instrumentation, so the caller owns transaction demarcation and database session identity\n"
            + "and Graphitron's owned-connection guarantees do not apply. It logs that once at wiring time.\n"
            + "For the managed path (one connection pinned per operation, identity mounted, transactions\n"
            + "demarcated) build the engine from {@code Graphitron.runtime(dataSource, dialect).newGraphQL(schema)}.\n"
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

    private static String escapeHatchJavadoc(List<ResolvedContextArg> contextArgs) {
        var sb = new StringBuilder();
        sb.append("Builds an {@link graphql.ExecutionInput.Builder} for the low-opinion escape-hatch path,\n");
        sb.append("pre-wired with a caller-supplied jOOQ {@code DSLContext} and any declared\n");
        sb.append("{@code contextArguments}, plus an empty {@link org.dataloader.DataLoaderRegistry}\n");
        sb.append("(required by graphql-java; generated fetchers populate it lazily on first lookup).\n");
        sb.append("\n");
        sb.append("<p>On this path <em>the caller owns everything</em>: transaction demarcation and session\n");
        sb.append("identity are the caller's responsibility and graphitron's owned-connection guarantees do\n");
        sb.append("not apply. For the opinionated path where graphitron pins the connection, mounts identity,\n");
        sb.append("and demarcates transactions, use {@link #newOwnedExecutionInput} with the engine from\n");
        sb.append("{@code Graphitron.runtime(...).newGraphQL(schema)}.\n");
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
        appendContextArgParams(sb, contextArgs);
        sb.append("@return a builder ready for {@code .query(...).build()}\n");
        return sb.toString();
    }

    private static String ownedExecutionInputJavadoc(List<ResolvedContextArg> contextArgs) {
        var sb = new StringBuilder();
        sb.append("Builds an {@link graphql.ExecutionInput.Builder} for the owned-connection path:\n");
        sb.append("pass only the opaque {@code claims} payload (typically the JWT) and any declared\n");
        sb.append("{@code contextArguments}. Unlike {@link #newExecutionInput}, no {@code DSLContext} is\n");
        sb.append("supplied here; the execution instrumentation wired by\n");
        sb.append("{@code Graphitron.runtime(...).newGraphQL(schema)} pins one connection per operation,\n");
        sb.append("mounts identity from the claims, produces the {@code DSLContext}, demarcates the\n");
        sb.append("operation's transactions, and releases at completion. Pair this factory with that engine.\n");
        sb.append("\n");
        sb.append("<p>The {@code claims} are stashed under the instrumentation's own request-claims key and\n");
        sb.append("are never parsed here; the connect hook interprets them in the database. An empty\n");
        sb.append("{@link org.dataloader.DataLoaderRegistry} is attached (generated fetchers populate it\n");
        sb.append("lazily). The contextArgument parameters, in alphabetical order, are null-checked and\n");
        sb.append("read back the same way as on the escape-hatch path.\n");
        sb.append("@param claims the opaque per-request claims payload (typically the JWT); must not be\n");
        sb.append("{@code null}\n");
        appendContextArgParams(sb, contextArgs);
        sb.append("@return a builder ready for {@code .query(...).build()}\n");
        return sb.toString();
    }

    private static void appendContextArgParams(StringBuilder sb, List<ResolvedContextArg> contextArgs) {
        for (ResolvedContextArg arg : contextArgs) {
            sb.append("@param ").append(arg.name())
              .append(" the per-request value for {@code contextArgument \"")
              .append(arg.name()).append("\"}; must not be {@code null}\n");
        }
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
