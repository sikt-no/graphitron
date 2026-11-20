package no.sikt.graphitron.rewrite.generators.schema;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.generators.util.ConnectionRuntimeClassGenerator;
import no.sikt.graphitron.rewrite.generators.util.GraphitronContextInterfaceGenerator;
import no.sikt.graphitron.rewrite.generators.util.OneOfDirectiveSdlGenerator;
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
 *   <li>{@code newOwnedExecutionInput(...)}, the owned-connection path, where the caller brings
 *       only the declared contextArguments (a configured mount's payload parameters among them)
 *       and the execution instrumentation pins connections on first demand, mounts identity, and
 *       produces every {@code DSLContext}.</li>
 * </ul>
 * They are distinct names, not overloads, so a caller cannot silently opt out of the owned-path
 * guarantees by passing a {@code DSLContext}; the escape-hatch name is frozen by additive-by-construction.
 * A build that accepts vouchers has no escape hatch: {@code _entities} acquires the vouched connection
 * from the request's source, which a caller-supplied {@code DSLContext} cannot be, so the factory is not
 * written at all and its absence is a compile error rather than a request-time refusal.
 *
 * <p>{@code schemaSdl(GraphQLSchema)} prints a built schema as the SDL to publish: on a federated
 * schema the way its {@code _service { sdl }} query answers, which is what Federation 2 composition
 * from a schema URL needs. The print policy lives here, in generated code that may name
 * federation-jvm, so a runtime library serving the SDL receives it as a function rather than
 * carrying a copy.
 *
 * <p>The sealed {@code GraphitronContext} permits only the generated
 * {@code GraphitronContextImpl} singleton; the factories are the per-request wiring point.
 */
public final class GraphitronFacadeGenerator {

    public static final String CLASS_NAME = "Graphitron";

    private static final String LOGGER_FIELD = "LOGGER";
    private static final String ESCAPE_HATCH_NOTICE_FIELD = "ESCAPE_HATCH_NOTICE_LOGGED";
    /** The owned factories' first parameter, the request's {@code ConnectionSource}. */
    public static final String SOURCE_PARAM = "source";

    private GraphitronFacadeGenerator() {}

    /**
     * @param federationLink whether the schema carries a federation {@code @link}; picks the
     *                       {@code buildSchema} overloads and the {@code schemaSdl} printer
     * @param usesOneOf      whether the assembled schema uses {@code @oneOf}; on the federated arm,
     *                       routes {@code schemaSdl} through the generated {@code OneOfDirectiveSdl}
     *                       helper, the same gate {@link GraphitronSchemaClassGenerator} applies to
     *                       its federation build
     * @param identity the graph's session identity as the store states it
     * @param vouches  whether the build writes the voucher runtime, so the runtime factories take the keys
     * @param escapeHatch whether the escape-hatch factory {@code newExecutionInput(dsl, ...)} is written:
     *                 false in a build that accepts vouchers, whose vouched connections come only from the
     *                 request's source
     */
    public static List<TypeSpec> generate(GraphitronSchema schema, String outputPackage,
                                          boolean federationLink, boolean usesOneOf,
                                          no.sikt.graphitron.emit.SessionIdentityRows identity, boolean vouches,
                                          boolean escapeHatch) {
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
        // The request's connection source: the owned factories' first parameter, written where the
        // connection instrumentation reads it. The escape hatch's caller brings a DSLContext instead.
        ClassName connectionSource = ClassName.get(schemaPackage,
            no.sikt.graphitron.rewrite.generators.util.ConnectionRuntimeClassGenerator.CONNECTION_SOURCE_CLASS_NAME);
        CodeBlock sourcePut = CodeBlock.of("b.put($S, $L);",
            no.sikt.graphitron.emit.SessionIdentityRows.CONTEXT_KEY, SOURCE_PARAM);

        var buildSchema = MethodSpec.methodBuilder("buildSchema")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(graphQLSchema)
            .addParameter(customizerType, "customizer")
            .addStatement("return $T.build(customizer)", graphitronSchema)
            .addJavadoc(buildSchemaJavadoc(federationLink))
            .build();

        var buildSchemaDefault = MethodSpec.methodBuilder("buildSchema")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(graphQLSchema)
            .addStatement("return buildSchema(customizer -> {})")
            .addJavadoc("The executable schema with no customization: {@code buildSchema(customizer -> {})}.\n"
                + "Written so an adapter can pass {@code Graphitron::buildSchema} wherever a schema\n"
                + "supplier is wanted.\n"
                + "@return the fully wired {@link graphql.schema.GraphQLSchema}\n")
            .build();

        // TreeMap iteration order is alphabetical by key: same order generated fetchers will
        // resolve them in, same order the user docs talk about them in, deterministic across runs.
        // Read off the schema's cached classification of the context keys the store states the
        // calls read.
        List<ResolvedContextArg> contextArgs = schema.contextArguments().resolved().values().stream().toList();

        // The factories are the same in every build: which tenants a request may reach is the
        // connection source's to decide, so no build adds a tenant parameter.
        var ownedConnections = ClassName.get(schemaPackage,
            no.sikt.graphitron.rewrite.generators.util.ConnectionRuntimeClassGenerator.OWNED_CONNECTIONS_CLASS_NAME);

        // The escape-hatch factory: the caller brings a DSLContext and owns transactions and
        // identity. Additive-by-construction keeps its name and shape frozen.
        var newExecutionInput = buildExecutionInputFactory(
            "newExecutionInput", dslContext, "defaultDsl",
            CodeBlock.of("b.put($T.class, defaultDsl);", dslContext),
            graphitronContext, graphitronContextImpl, executionInput, executionInputBuilder,
            dataLoaderRegistry, contextArgs,
            escapeHatchJavadoc(contextArgs));

        // The owned-connection factory: the caller brings only the declared contextArguments
        // (the mount's payload parameters among them, ordinary name-keyed slots); the execution
        // instrumentation publishes the connection carrier, and fetchers pin on first demand.
        var newOwnedExecutionInput = buildExecutionInputFactory(
            "newOwnedExecutionInput", connectionSource, SOURCE_PARAM, sourcePut,
            graphitronContext, graphitronContextImpl, executionInput, executionInputBuilder,
            dataLoaderRegistry, contextArgs,
            ownedExecutionInputJavadoc(contextArgs));

        // Emit the caller-owns-everything notice once per process, even if the engine is rebuilt.
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
        var sqlDialect = ClassName.get("org.jooq", "SQLDialect");
        var voucherKeys = ClassName.get(schemaPackage, no.sikt.graphitron.emit.VoucherRuntime.KEYS);
        String keysArg = vouches ? ", voucherKeys" : "";
        String keysParamDoc = vouches
            ? "@param voucherKeys the keys this subgraph signs and verifies vouchers with; must not be {@code null}\n"
            : "";
        var runtimeBuilder = MethodSpec.methodBuilder("runtime")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(graphitronRuntime)
            .addParameter(sqlDialect, "dialect");
        if (vouches) {
            runtimeBuilder.addParameter(voucherKeys, "voucherKeys");
        }
        var runtime = runtimeBuilder
            .addStatement("return new $T(dialect$L)", graphitronRuntime, keysArg)
            .addJavadoc(runtimeJavadoc(escapeHatch).replace("@return", keysParamDoc + "@return"))
            .build();

        var settings = ClassName.get("org.jooq.conf", "Settings");
        var runtimeWithSettingsBuilder = MethodSpec.methodBuilder("runtime")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(graphitronRuntime)
            .addParameter(sqlDialect, "dialect")
            .addParameter(settings, "settings");
        if (vouches) {
            runtimeWithSettingsBuilder.addParameter(voucherKeys, "voucherKeys");
        }
        var runtimeWithSettings = runtimeWithSettingsBuilder
            .addStatement("return new $T(dialect, settings$L)", graphitronRuntime, keysArg)
            .addJavadoc("Builds the application-scoped runtime over a dialect and jOOQ {@code Settings} (schema\n"
                + "or render mapping), so they reach every context graphitron mints over a leased connection.\n"
                + "Otherwise as {@link #runtime(org.jooq.SQLDialect"
                + (vouches ? ", " + voucherKeys.canonicalName() : "") + ")}.\n"
                + "@param dialect the jOOQ {@code SQLDialect} for this database; must not be {@code null}\n"
                + "@param settings the jOOQ {@code Settings}; {@code null} means defaults\n"
                + keysParamDoc
                + "@return the application-scoped runtime, to be held for the app's lifetime\n")
            .build();

        var optional = ClassName.get("java.util", "Optional");
        var requestRejection = MethodSpec.methodBuilder("requestRejection")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(ParameterizedTypeName.get(optional, ClassName.get(Throwable.class)))
            .addParameter(Throwable.class, "thrown")
            .addStatement("return $T.rejectionCause(thrown)", ownedConnections)
            .addJavadoc("What rejected the request, when {@code thrown} is, or is caused by, the generated\n"
                + "{@code RequestRejectedException}; empty otherwise. For a failed acquisition it is what the\n"
                + "request's connection source threw; for a failed voucher check or a second vouched-for\n"
                + "value it is the rejection itself, which names the check or the limit and carries nothing\n"
                + "of any voucher. {@code execute} throws the rejection in place of a result, so an\n"
                + "application that serves over HTTP passes this to its adapter and answers the request\n"
                + "from what rejected it (a voucher rejection usually as 401 or 403, an acquisition fault\n"
                + "usually as 503), without naming the generated exception.\n"
                + "@param thrown what escaped execution\n"
                + "@return what rejected the request, if a request rejection is on the cause chain\n")
            .build();

        var self = ClassName.get(outputPackage, CLASS_NAME);
        var logger = ClassName.get("org.slf4j", "Logger");
        var loggerFactory = ClassName.get("org.slf4j", "LoggerFactory");
        var atomicBoolean = ClassName.get("java.util.concurrent.atomic", "AtomicBoolean");
        var loggerField = com.palantir.javapoet.FieldSpec.builder(
                logger, LOGGER_FIELD, Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
            .initializer("$T.getLogger($T.class)", loggerFactory, self)
            .build();
        var escapeHatchNoticeField = com.palantir.javapoet.FieldSpec.builder(
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
            .addMethod(buildSchemaDefault);
        if (escapeHatch) {
            classBuilder.addMethod(newExecutionInput);
        }
        classBuilder.addMethod(newOwnedExecutionInput)
            .addMethod(newGraphQL)
            .addMethod(runtime)
            .addMethod(runtimeWithSettings)
            .addMethod(schemaSdl(graphQLSchema, outputPackage, federationLink, usesOneOf))
            .addMethod(requestRejection);

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
        return generate(schema, outputPackage, false, false, no.sikt.graphitron.emit.SessionIdentityRows.empty(), false, true);
    }

    /**
     * Emits {@code schemaSdl(GraphQLSchema)}, the SDL a consumer publishes for the schema it
     * executes. The federated arm prints the way the runtime {@code _service { sdl }} answers
     * ({@code ServiceSDLPrinter.generateServiceSDLV2}, plus the {@code @oneOf} definition through
     * the generated helper when the schema uses it); the plain arm uses the runtime-meaningful
     * options of {@link SchemaSdlEmitter}'s plain print, whose directive and support-type filters
     * are vacuous over the schema {@code GraphitronSchema.build} assembles.
     */
    private static MethodSpec schemaSdl(ClassName graphQLSchema, String outputPackage,
                                        boolean federationLink, boolean usesOneOf) {
        var method = MethodSpec.methodBuilder("schemaSdl")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(String.class)
            .addParameter(graphQLSchema, "schema");
        if (federationLink && usesOneOf) {
            method.addStatement("return $T.$L(schema)",
                ClassName.get(outputPackage + ".util", OneOfDirectiveSdlGenerator.CLASS_NAME),
                OneOfDirectiveSdlGenerator.SERVICE_SDL_METHOD);
        } else if (federationLink) {
            method.addStatement("return $T.generateServiceSDLV2(schema)",
                ClassName.get("com.apollographql.federation.graphqljava.printer", "ServiceSDLPrinter"));
        } else {
            var schemaPrinter = ClassName.get("graphql.schema.idl", "SchemaPrinter");
            method.addCode("return new $T($T.Options.defaultOptions()\n", schemaPrinter, schemaPrinter)
                .addCode("        .includeSchemaDefinition(true)\n")
                .addCode("        .includeScalarTypes(true)\n")
                .addCode("        .includeIntrospectionTypes(false))\n")
                .addCode("    .print(schema);\n");
        }
        return method.addJavadoc(schemaSdlJavadoc(federationLink)).build();
    }

    private static String schemaSdlJavadoc(boolean federationLink) {
        return "Prints {@code schema} as the SDL to publish for this subgraph, for example from an\n"
            + "HTTP schema endpoint. Pass the schema the application executes, so anything a\n"
            + "{@code buildSchema} customizer added is printed too.\n"
            + "\n"
            + (federationLink
               ? "<p>Printed the way the federation {@code _service { sdl }} query answers: the\n"
               + "{@code schema @link(...)} declaration included and the GraphQL built-in directive\n"
               + "definitions left out, the form Federation 2 composition expects.\n"
               : "<p>Printed with the {@code schema} block and every scalar, and without the\n"
               + "introspection types, matching the generated {@code schema.graphqls}.\n")
            + "@param schema the executed schema, typically {@code buildSchema(...)}'s result\n"
            + "@return the printed SDL\n";
    }

    /**
     * Emits one {@code ExecutionInput.Builder} factory. The two factories, the escape-hatch
     * {@code newExecutionInput(DSLContext, ...)} and the owned-path
     * {@code newOwnedExecutionInput(...)}, differ only in the escape hatch's leading
     * {@code DSLContext} parameter and the single {@code graphQLContext} entry it produces
     * ({@code firstPut}; the owned form passes none, so the typed {@code DSLContext.class} key
     * belongs to the escape hatch alone); everything schema-shaped (the alphabetical
     * contextArgument parameters, their null-checks, the {@code GraphitronContext} singleton, the
     * empty {@code DataLoaderRegistry}) is identical and single-sourced here so the two cannot
     * drift. No build adds a tenant parameter: the connection source decides which tenants a
     * request may reach.
     */
    private static MethodSpec buildExecutionInputFactory(
            String methodName, ClassName firstParamType, String firstParamName, CodeBlock firstPut,
            ClassName graphitronContext, ClassName graphitronContextImpl,
            ClassName executionInput, ClassName executionInputBuilder,
            ClassName dataLoaderRegistry, List<ResolvedContextArg> contextArgs, String javadoc) {
        var method = MethodSpec.methodBuilder(methodName)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(executionInputBuilder);
        if (firstParamType != null) {
            method.addParameter(firstParamType, firstParamName);
        }
        for (ResolvedContextArg arg : contextArgs) {
            method.addParameter(arg.javaType(), arg.name());
        }
        if (firstParamType != null) {
            method.addStatement("$T.requireNonNull($L, $S)", Objects.class, firstParamName, firstParamName);
        }
        for (ResolvedContextArg arg : contextArgs) {
            method.addStatement("$T.requireNonNull($L, $S)", Objects.class, arg.name(), arg.name());
        }

        // The downstream `graphitronContext(env)` helper retrieves the GraphitronContextImpl
        // singleton by its typed key; per-request values flow through env.getGraphQlContext()
        // reads inside the singleton's default methods.
        method.addCode("return $T.newExecutionInput()\n", executionInput);
        method.addCode("    .graphQLContext(b -> {\n");
        if (firstPut != null) {
            method.addCode("        $L\n", firstPut);
        }
        for (ResolvedContextArg arg : contextArgs) {
            method.addCode("        b.put($S, $L);\n", arg.name(), arg.name());
        }
        method.addCode("        b.put($T.class, $T.INSTANCE);\n", graphitronContext, graphitronContextImpl);
        method.addCode("    })\n");
        method.addCode("    .dataLoaderRegistry(new $T());\n", dataLoaderRegistry);
        method.addJavadoc(javadoc);
        return method.build();
    }

    private static String runtimeJavadoc(boolean escapeHatch) {
        return "Builds the application-scoped {@code GraphitronRuntime} that owns the connection\n"
            + "lifecycle: it leases each request's connections from the request's {@code ConnectionSource}\n"
            + "on first demand, demarcates operation-typed transactions, and releases every lease exactly\n"
            + "once. Built once at wiring time over the jOOQ dialect every context renders with:\n"
            + "{@code var runtime = Graphitron.runtime(SQLDialect.POSTGRES);}.\n"
            + "\n"
            + "<p>This is the opinionated path: per request you call\n"
            + "{@code Graphitron.newOwnedExecutionInput(source, ...)} and graphitron leases connections\n"
            + "from {@code source} and releases them at completion. "
            + (escapeHatch
                ? "The lower-opinion escape\n"
                    + "hatch is the static {@code Graphitron.newExecutionInput(dsl, ...)} form, where the caller\n"
                    + "owns the {@code DSLContext}, transaction demarcation, and identity state.\n"
                : "This build accepts vouchers, so it has no\n"
                    + "escape hatch: a vouched connection comes only from the request's source.\n")
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
        sb.append("pass the request's {@code ConnectionSource} and then only the declared\n");
        sb.append("{@code contextArguments}. Unlike the escape hatch, no {@code DSLContext} is supplied\n");
        sb.append("here; the execution instrumentation wired by\n");
        sb.append("{@code Graphitron.runtime(...).newGraphQL(schema)} leases connections from the source on\n");
        sb.append("first demand, produces every {@code DSLContext}, demarcates\n");
        sb.append("the operation's transactions, and releases at completion. Pair this factory with that\n");
        sb.append("engine.\n");
        sb.append("\n");
        sb.append("<p>An empty {@link org.dataloader.DataLoaderRegistry} is attached (generated fetchers\n");
        sb.append("populate it lazily). The contextArgument parameters, in alphabetical order, are\n");
        sb.append("null-checked and read back the same way as on the escape-hatch path.\n");
        sb.append("@param ").append(SOURCE_PARAM)
          .append(" where this request's connections come from: one lease per connection key,\n");
        sb.append("released by graphitron exactly once; must not be {@code null}\n");
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
