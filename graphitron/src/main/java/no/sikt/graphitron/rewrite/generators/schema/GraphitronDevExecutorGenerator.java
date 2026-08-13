package no.sikt.graphitron.rewrite.generators.schema;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.FieldSpec;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.generators.util.ConnectionRuntimeClassGenerator;
import no.sikt.graphitron.rewrite.generators.util.GraphitronConnectionInstrumentationGenerator;
import no.sikt.graphitron.rewrite.generators.util.GraphitronTransactionProviderGenerator;
import no.sikt.graphitron.rewrite.model.ResolvedContextArg;
import no.sikt.graphitron.rewrite.session.SessionHooks;

import javax.lang.model.element.Modifier;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Generates the {@code GraphitronDevExecutor} class in {@code <outputPackage>} (the root output
 * package, beside the {@code Graphitron} facade): the in-process query-execution entry point
 * the {@code graphitron:dev} MCP {@code execute} tool drives reflectively.
 *
 * <p>The load-bearing property is the signature: one public static {@code execute} method whose
 * parameter and return types are JDK-only ({@code java.sql.Connection}, {@code String},
 * {@code java.util.Map}), so the dev-loop host reflects exactly one method and no jOOQ or
 * graphql-java type ever crosses the host-to-generated-classloader boundary. Everything
 * schema-varying (the {@code newOwnedExecutionInput} signature, the typed contextArgument
 * binding, whether {@code <sessionState>} is configured) is absorbed here at generation time,
 * compiled in the same pass over the same classpath as the rest of the closure.
 *
 * <p>Inside, the executor is a plain consumer of the owned-connection machinery: it wraps the host's single
 * dev connection in a one-connection {@code DataSource}, constructs the {@code GraphitronRuntime}
 * with the requested dialect, and attaches the connection instrumentation with the
 * {@code ROLLBACK_ONLY} commit policy so mutations settle by rolling back; the dev loop exercises
 * the same acquisition/hook/transaction path a real application does, and exploration can never
 * persist a write.
 *
 * <p>Emission is gated to non-federation schemas: a federation subgraph builds through the
 * two-arg {@code buildSchema(schemaCustomizer, federationCustomizer)} and needs an entity
 * fetcher this executor does not supply. The compile-dependency graph models the unit
 * unconditionally, which is superset-safe: the render skips units never emitted.
 */
public final class GraphitronDevExecutorGenerator {

    public static final String CLASS_NAME = "GraphitronDevExecutor";
    public static final String EXECUTE_METHOD = "execute";

    private static final String DATA_SOURCE_WRAPPER = "SingleConnectionDataSource";
    private static final String CONTEXT_ARG_HELPER = "requiredContextArg";

    private GraphitronDevExecutorGenerator() {}

    public static List<TypeSpec> generate(GraphitronSchema schema, String outputPackage,
            SessionHooks sessionHooks, boolean federationLink) {
        if (federationLink) {
            return List.of();
        }
        String schemaPackage = outputPackage + ".schema";
        var facade = ClassName.get(outputPackage, GraphitronFacadeGenerator.CLASS_NAME);
        var runtime = ClassName.get(schemaPackage, ConnectionRuntimeClassGenerator.RUNTIME_CLASS_NAME);
        var instrumentation = ClassName.get(schemaPackage, GraphitronConnectionInstrumentationGenerator.CLASS_NAME);
        var transactionProvider = ClassName.get(schemaPackage, GraphitronTransactionProviderGenerator.CLASS_NAME);
        var commitPolicy = transactionProvider.nestedClass(GraphitronTransactionProviderGenerator.COMMIT_POLICY_ENUM_NAME);
        var connection = ClassName.get("java.sql", "Connection");
        var sqlDialect = ClassName.get("org.jooq", "SQLDialect");
        var graphQL = ClassName.get("graphql", "GraphQL");
        var executionInput = ClassName.get("graphql", "ExecutionInput");
        var executionResult = ClassName.get("graphql", "ExecutionResult");
        var jsonValue = ClassName.get("org.jooq.tools.json", "JSONValue");
        var mapStringObject = ParameterizedTypeName.get(
            ClassName.get(Map.class), ClassName.get(String.class), ClassName.get(Object.class));

        List<ResolvedContextArg> contextArgs = schema.contextArguments().resolved().values().stream().toList();
        // The facade's factory signatures fork on the same predicate; reading the one schema
        // seam keeps this call and the emitted parameter list from drifting.
        boolean fanOut = schema.hasFanOutBinding();

        // The fail-loud question and the payload shape are read off the resolved carrier, never
        // re-derived here: the string-constructible predicate is SessionHooks' own.
        boolean mountsIdentity = sessionHooks.emitsHookImplementation();
        String stringPayloadName = sessionHooks.stringConstructiblePayload().orElse(null);
        boolean payloadUnconstructible = mountsIdentity
            && !sessionHooks.payloadParams().isEmpty()
            && stringPayloadName == null;

        var execute = MethodSpec.methodBuilder(EXECUTE_METHOD)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(ClassName.get(String.class))
            .addParameter(connection, "connection")
            .addParameter(ClassName.get(String.class), "dialect")
            .addParameter(ClassName.get(String.class), "query")
            .addParameter(mapStringObject, "variables")
            .addParameter(ClassName.get(String.class), "claims")
            .addParameter(mapStringObject, "contextArgs")
            .addJavadoc(executeJavadoc(stringPayloadName != null, payloadUnconstructible, contextArgs));
        if (payloadUnconstructible) {
            // Degrade with a message naming the limitation rather than requiring consumers to
            // write a parse method for a dev tool's benefit: <devDatabase><claims> supplies one
            // string, which covers exactly a single-String mount payload.
            var mount = sessionHooks.mountRef().orElseThrow();
            execute.addStatement("throw new $T($S)", IllegalStateException.class,
                "This schema's <mount> (" + mount.className() + "#" + mount.methodName() + ") takes a "
                    + "payload the dev executor cannot construct from the one claims string it is given. "
                    + "Dev execution supports a mount whose payload is a single java.lang.String (or "
                    + "none); point <mount> at a facade with a String payload for dev use, or execute "
                    + "against a running application.");
            var degradedClass = TypeSpec.classBuilder(CLASS_NAME)
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addJavadoc(classJavadoc())
                .addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PRIVATE).build())
                .addMethod(execute.build())
                .addType(singleConnectionDataSource(connection));
            return List.of(degradedClass.build());
        }
        if (contextArgs.stream().anyMatch(arg -> arg.javaType() instanceof ParameterizedTypeName)) {
            execute.addAnnotation(no.sikt.graphitron.javapoet.AnnotationSpec
                .builder(SuppressWarnings.class).addMember("value", "$S", "unchecked").build());
        }
        execute
            .addStatement("$T.requireNonNull(connection, $S)", Objects.class, "connection")
            .addStatement("$T.requireNonNull(dialect, $S)", Objects.class, "dialect")
            .addStatement("$T.requireNonNull(query, $S)", Objects.class, "query");
        if (stringPayloadName != null) {
            // Fail loud, never skip: running without the mount's identity would execute under a
            // different security posture than production (seeing nothing under RLS, or
            // everything on a convention-fence setup).
            execute.beginControlFlow("if (claims == null || claims.isBlank())")
                .addStatement("throw new $T($S)", IllegalStateException.class,
                    "This schema configures <sessionState>, so dev execution mounts identity "
                        + "through your mount method and requires a claims payload. Supply it via "
                        + "the GRAPHITRON_DEV_CLAIMS environment variable (inline, or @/path/to/file) "
                        + "or the graphitron-maven-plugin dev database configuration.")
                .endControlFlow()
                .addStatement("$T claimsPayload = claims", String.class);
        }
        if (mountsIdentity) {
            // Fail fast with the mount method's own error: inside execution the error router
            // redacts fetcher failures behind a correlation id, but a rejected dev payload is the
            // caller's to fix, so the executor runs the pair once up front and lets the method's
            // exception propagate verbatim. Execution's own mount then overwrites this one on the
            // same connection (mount establishes identity wholesale).
            var hookImpl = ClassName.get(schemaPackage,
                ConnectionRuntimeClassGenerator.SESSION_HOOK_IMPL_CLASS_NAME);
            var settings = ClassName.get("org.jooq.conf", "Settings");
            execute
                .addComment("Preflight: surface a rejected payload as the mount method's own exception.")
                .addStatement("$T devDialect = $T.valueOf(dialect)", sqlDialect, sqlDialect)
                .addStatement("$T devSettings = new $T()", settings, settings);
            String payloadArg = stringPayloadName == null ? "" : ", claimsPayload";
            if (sessionHooks instanceof SessionHooks.Handled && sessionHooks.unmountRef().isPresent()) {
                execute.addStatement(
                    "var preflightHandle = $T.mount(connection, devDialect, devSettings$L)",
                    hookImpl, payloadArg);
                execute.addStatement(
                    "$T.unmount(connection, devDialect, devSettings, preflightHandle)", hookImpl);
            } else {
                execute.addStatement("$T.mount(connection, devDialect, devSettings$L)",
                    hookImpl, payloadArg);
                if (sessionHooks.unmountRef().isPresent()) {
                    execute.addStatement("$T.unmount(connection, devDialect, devSettings)", hookImpl);
                }
            }
        }
        execute
            .addStatement("$T runtime = new $T(new $N(connection), $T.valueOf(dialect))",
                runtime, runtime, DATA_SOURCE_WRAPPER, sqlDialect)
            .addStatement("$T engine = $T.newGraphQL($T.buildSchema(builder -> {}))\n"
                    + "    .instrumentation(new $T(runtime, $T.ROLLBACK_ONLY))\n"
                    + "    .build()",
                graphQL, graphQL, facade, instrumentation, commitPolicy)
            .addStatement("$T input = $T.$L$L\n"
                    + "    .query(query)\n"
                    + "    .variables(variables == null ? $T.of() : variables)\n"
                    + "    .build()",
                executionInput, facade, "newOwnedExecutionInput",
                ownedFactoryArgs(contextArgs, fanOut, stringPayloadName),
                ClassName.get(Map.class))
            .addStatement("$T result = engine.execute(input)", executionResult)
            .addStatement("return $T.toJSONString(result.toSpecification())", jsonValue);

        var classBuilder = TypeSpec.classBuilder(CLASS_NAME)
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addJavadoc(classJavadoc())
            .addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PRIVATE).build())
            .addMethod(execute.build());
        if (contextArgs.stream().anyMatch(arg -> !arg.name().equals(stringPayloadName))) {
            classBuilder.addMethod(contextArgHelper());
        }
        classBuilder.addType(singleConnectionDataSource(connection));
        return List.of(classBuilder.build());
    }

    /**
     * The argument list for the {@code newOwnedExecutionInput} call: one typed extraction per
     * contextArgument in the classifier's alphabetical order (the same {@code resolved()} order
     * the facade's factory parameters use, so the two cannot disagree on position). The mount's
     * single-String payload slot, when the schema has one, reads the dev host's claims string
     * instead of the contextArgs map.
     */
    private static CodeBlock ownedFactoryArgs(List<ResolvedContextArg> contextArgs, boolean fanOut,
            String stringPayloadName) {
        var args = CodeBlock.builder().add("(");
        boolean first = true;
        if (fanOut) {
            // The dev executor runs single-connection with no tenant map, so the fan-out domain
            // is structurally empty: an empty collection satisfies the factory slot, and a fanned
            // field resolves to an empty union rather than a missing-parameter failure.
            args.add("java.util.List.of()");
            first = false;
        }
        for (ResolvedContextArg arg : contextArgs) {
            if (!first) {
                args.add(",\n        ");
            }
            first = false;
            if (arg.name().equals(stringPayloadName)) {
                args.add("claimsPayload");
            } else {
                args.add("($T) $N(contextArgs, $S, $T.class)",
                    arg.javaType(), CONTEXT_ARG_HELPER, arg.name(), rawType(arg.javaType()));
            }
        }
        return args.add(")").build();
    }

    private static TypeName rawType(TypeName type) {
        return type instanceof ParameterizedTypeName parameterized ? parameterized.rawType() : type;
    }

    /**
     * Null-checks and coerces one contextArgument value out of the caller's map. JSON-sourced
     * numbers arrive as whatever width the host's parser chose (an {@code Integer} where the
     * schema declared {@code Long}), so numeric targets are widened through {@code Number}
     * rather than cast directly.
     */
    private static MethodSpec contextArgHelper() {
        var mapStringObject = ParameterizedTypeName.get(
            ClassName.get(Map.class), ClassName.get(String.class), ClassName.get(Object.class));
        return MethodSpec.methodBuilder(CONTEXT_ARG_HELPER)
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(ClassName.get(Object.class))
            .addParameter(mapStringObject, "contextArgs")
            .addParameter(ClassName.get(String.class), "name")
            .addParameter(ParameterizedTypeName.get(ClassName.get(Class.class),
                no.sikt.graphitron.javapoet.WildcardTypeName.subtypeOf(Object.class)), "type")
            .addStatement("$T value = contextArgs == null ? null : contextArgs.get(name)", Object.class)
            .beginControlFlow("if (value == null)")
            .addStatement("throw new $T(\"contextArgs is missing required entry '\" + name + \"' (expected \" + type.getName() + \")\")",
                IllegalArgumentException.class)
            .endControlFlow()
            .beginControlFlow("if (value instanceof $T number && !type.isInstance(value))", Number.class)
            .addStatement("if (type == $T.class) return number.longValue()", Long.class)
            .addStatement("if (type == $T.class) return number.intValue()", Integer.class)
            .addStatement("if (type == $T.class) return number.doubleValue()", Double.class)
            .addStatement("if (type == $T.class) return number.floatValue()", Float.class)
            .addStatement("if (type == $T.class) return number.shortValue()", Short.class)
            .addStatement("if (type == $T.class) return new $T(number.toString())",
                ClassName.get("java.math", "BigDecimal"), ClassName.get("java.math", "BigDecimal"))
            .endControlFlow()
            .addStatement("return value")
            .build();
    }

    /**
     * The one-connection {@code DataSource} the executor hands the runtime: the owned-connection
     * acquisition path asks a {@code DataSource} for its per-operation connection, and in the dev loop that
     * connection is the single host-opened one. Release closes it (the runtime treats close as
     * return-to-pool), which is fine: the host's own {@code close()} in its {@code finally} is
     * then an idempotent no-op.
     */
    private static TypeSpec singleConnectionDataSource(ClassName connection) {
        var dataSource = ClassName.get("javax.sql", "DataSource");
        var sqlException = ClassName.get("java.sql", "SQLException");
        var printWriter = ClassName.get("java.io", "PrintWriter");
        var typeVariable = no.sikt.graphitron.javapoet.TypeVariableName.get("T");
        return TypeSpec.classBuilder(DATA_SOURCE_WRAPPER)
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
            .addSuperinterface(dataSource)
            .addField(FieldSpec.builder(connection, "connection", Modifier.PRIVATE, Modifier.FINAL).build())
            .addMethod(MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PRIVATE)
                .addParameter(connection, "connection")
                .addStatement("this.connection = connection")
                .build())
            .addMethod(MethodSpec.methodBuilder("getConnection")
                .addModifiers(Modifier.PUBLIC).addAnnotation(Override.class)
                .returns(connection)
                .addStatement("return connection")
                .build())
            .addMethod(MethodSpec.methodBuilder("getConnection")
                .addModifiers(Modifier.PUBLIC).addAnnotation(Override.class)
                .returns(connection)
                .addParameter(ClassName.get(String.class), "username")
                .addParameter(ClassName.get(String.class), "password")
                .addStatement("return connection")
                .build())
            .addMethod(MethodSpec.methodBuilder("getLogWriter")
                .addModifiers(Modifier.PUBLIC).addAnnotation(Override.class)
                .returns(printWriter)
                .addStatement("return null")
                .build())
            .addMethod(MethodSpec.methodBuilder("setLogWriter")
                .addModifiers(Modifier.PUBLIC).addAnnotation(Override.class)
                .addParameter(printWriter, "out")
                .build())
            .addMethod(MethodSpec.methodBuilder("setLoginTimeout")
                .addModifiers(Modifier.PUBLIC).addAnnotation(Override.class)
                .addParameter(TypeName.INT, "seconds")
                .build())
            .addMethod(MethodSpec.methodBuilder("getLoginTimeout")
                .addModifiers(Modifier.PUBLIC).addAnnotation(Override.class)
                .returns(TypeName.INT)
                .addStatement("return 0")
                .build())
            .addMethod(MethodSpec.methodBuilder("getParentLogger")
                .addModifiers(Modifier.PUBLIC).addAnnotation(Override.class)
                .returns(ClassName.get("java.util.logging", "Logger"))
                .addException(ClassName.get("java.sql", "SQLFeatureNotSupportedException"))
                .addStatement("throw new $T()", ClassName.get("java.sql", "SQLFeatureNotSupportedException"))
                .build())
            .addMethod(MethodSpec.methodBuilder("unwrap")
                .addModifiers(Modifier.PUBLIC).addAnnotation(Override.class)
                .addTypeVariable(typeVariable)
                .returns(typeVariable)
                .addParameter(ParameterizedTypeName.get(ClassName.get(Class.class), typeVariable), "iface")
                .addException(sqlException)
                .addStatement("throw new $T(\"not a wrapper\")", sqlException)
                .build())
            .addMethod(MethodSpec.methodBuilder("isWrapperFor")
                .addModifiers(Modifier.PUBLIC).addAnnotation(Override.class)
                .returns(TypeName.BOOLEAN)
                .addParameter(ParameterizedTypeName.get(ClassName.get(Class.class),
                    no.sikt.graphitron.javapoet.WildcardTypeName.subtypeOf(Object.class)), "iface")
                .addStatement("return false")
                .build())
            .build();
    }

    private static String classJavadoc() {
        return "Dev-loop query execution entry point: runs a GraphQL operation against this\n"
            + "schema's generated resolvers in-process, on a caller-supplied JDBC connection, and\n"
            + "returns the {@code ExecutionResult.toSpecification()} JSON. Driven reflectively by the\n"
            + "{@code graphitron:dev} MCP {@code execute} tool; not part of the application runtime\n"
            + "surface, and not intended to be called from application code.\n"
            + "\n"
            + "<p>The {@code execute} signature is deliberately JDK-only so the dev-loop host can\n"
            + "invoke it across classloaders without sharing any jOOQ or graphql-java types.\n"
            + "\n"
            + "<p>Execution goes through the same owned-connection machinery a real application\n"
            + "uses ({@code GraphitronRuntime}, the connection instrumentation, the session hooks),\n"
            + "with the {@code ROLLBACK_ONLY} commit policy's deliberate divergences: one deferred\n"
            + "operation transaction with savepoint-scoped mutation fields instead of per-field\n"
            + "commits (so payload read-backs observe the writes), and everything discarded at\n"
            + "release (dev exploration never persists a write).\n";
    }

    private static String executeJavadoc(boolean claimsFeedsPayload, boolean payloadUnconstructible,
            List<ResolvedContextArg> contextArgs) {
        var sb = new StringBuilder();
        sb.append("Executes one GraphQL operation against the generated schema on {@code connection}\n");
        sb.append("and returns the JSON-serialized {@code ExecutionResult.toSpecification()} (data plus\n");
        sb.append("GraphQL errors; execution failures surface as GraphQL errors in the payload rather\n");
        sb.append("than exceptions).\n");
        sb.append("\n");
        sb.append("<p>Mutations run under the {@code ROLLBACK_ONLY} commit policy: each mutation\n");
        sb.append("field's transaction rolls back at settle, so writes are observable in the response\n");
        sb.append("but never persist.\n");
        if (claimsFeedsPayload) {
            sb.append("\n");
            sb.append("<p>This schema configures {@code <sessionState>}: the mount method receives\n");
            sb.append("{@code claims} as its single String payload exactly as a production caller would\n");
            sb.append("supply it, and a missing or blank payload fails loudly rather than running\n");
            sb.append("unsecured. The payload is preflighted through the mount method before execution,\n");
            sb.append("so a rejected payload propagates as the method's own exception rather than a\n");
            sb.append("redacted execution error.\n");
        }
        if (payloadUnconstructible) {
            sb.append("\n");
            sb.append("<p>This schema's {@code <mount>} takes a payload the dev executor cannot construct\n");
            sb.append("from one string, so every call fails loudly naming the limitation.\n");
        }
        sb.append("@param connection the open JDBC connection to execute on; the caller owns it, but\n");
        sb.append("release may close it (idempotent with the caller's own close)\n");
        sb.append("@param dialect the jOOQ {@code SQLDialect} name for this database, e.g. {@code POSTGRES}\n");
        sb.append("or {@code ORACLE}; must not be {@code null}\n");
        sb.append("@param query the GraphQL operation to execute; must not be {@code null}\n");
        sb.append("@param variables the operation's variables; {@code null} means none\n");
        if (claimsFeedsPayload) {
            sb.append("@param claims the per-request payload handed to the mount method;\n");
            sb.append("must not be {@code null} or blank\n");
        } else {
            sb.append("@param claims unused by this schema (its mount takes no String payload); may be\n");
            sb.append("{@code null}\n");
        }
        if (contextArgs.isEmpty()) {
            sb.append("@param contextArgs unused by this schema (no {@code contextArguments} declared); may\n");
            sb.append("be {@code null}\n");
        } else {
            sb.append("@param contextArgs one entry per declared {@code contextArgument}: ");
            sb.append(String.join(", ", contextArgs.stream()
                .map(arg -> "{@code " + arg.name() + "} (" + arg.javaType() + ")").toList()));
            sb.append("; all required\n");
        }
        sb.append("@return the execution result as a JSON string\n");
        return sb.toString();
    }
}
