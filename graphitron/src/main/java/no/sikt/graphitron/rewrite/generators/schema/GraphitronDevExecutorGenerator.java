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
import no.sikt.graphitron.emit.SessionIdentityRows;

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
 * <p>A federation subgraph gets one too: the facade's one-arg {@code buildSchema} builds the
 * federation-wrapped schema with the default entity fetcher, so the executor runs
 * {@code _entities} and {@code _service} the way the subgraph serves them.
 */
public final class GraphitronDevExecutorGenerator {

    public static final String CLASS_NAME = "GraphitronDevExecutor";
    public static final String EXECUTE_METHOD = "execute";

    private static final String DATA_SOURCE_WRAPPER = "SingleConnectionDataSource";
    private static final String CONTEXT_ARG_HELPER = "requiredContextArg";

    private GraphitronDevExecutorGenerator() {}

    public static List<TypeSpec> generate(GraphitronSchema schema, String outputPackage,
            SessionIdentityRows identity) {
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
        // The facade's factory signatures fork on the same fact; reading the one schema seam
        // keeps this call and the emitted parameter list from drifting.
        boolean multiTenant = schema.requestTenantKeyType().isPresent();

        // Whether identity is mounted is read off the store's rows, never re-derived here. When it
        // is, the executor takes the request's SessionState where it would take claims: the dev
        // host builds it from <devDatabase><session>, which keeps the generated code free of
        // reflection and the host free of any generated type.
        boolean mountsIdentity = identity.emitsHookImplementation();
        var sessionStateType = ClassName.get(schemaPackage,
            ConnectionRuntimeClassGenerator.SESSION_STATE_CLASS_NAME);

        var execute = MethodSpec.methodBuilder(EXECUTE_METHOD)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(ClassName.get(String.class))
            .addParameter(connection, "connection")
            .addParameter(ClassName.get(String.class), "dialect")
            .addParameter(ClassName.get(String.class), "query")
            .addParameter(mapStringObject, "variables");
        if (mountsIdentity) {
            execute.addParameter(sessionStateType, "session").addException(Exception.class);
        } else {
            execute.addParameter(ClassName.get(String.class), "claims");
        }
        execute
            .addParameter(mapStringObject, "contextArgs")
            .addJavadoc(executeJavadoc(mountsIdentity, contextArgs));
        if (contextArgs.stream().anyMatch(arg -> arg.javaType() instanceof ParameterizedTypeName)) {
            execute.addAnnotation(no.sikt.graphitron.javapoet.AnnotationSpec
                .builder(SuppressWarnings.class).addMember("value", "$S", "unchecked").build());
        }
        execute
            .addStatement("$T.requireNonNull(connection, $S)", Objects.class, "connection")
            .addStatement("$T.requireNonNull(dialect, $S)", Objects.class, "dialect")
            .addStatement("$T.requireNonNull(query, $S)", Objects.class, "query");
        if (mountsIdentity) {
            // Fail loud, never skip: running without the identity a production caller supplies
            // would execute under a different security posture (seeing nothing under RLS, or
            // everything on a convention-fence setup).
            execute.beginControlFlow("if (session == null)")
                .addStatement("throw new $T($S)", IllegalStateException.class,
                    "This schema configures <sessionState>, so dev execution mounts identity through "
                        + "your SessionState and needs one: name the method that builds it from the "
                        + "claims payload in the graphitron-maven-plugin dev database configuration "
                        + "(<devDatabase><session>fqcn#method</session>, or GRAPHITRON_DEV_SESSION), "
                        + "next to the claims payload.")
                .endControlFlow();
            // Fail fast with the callback's own error: inside execution the error router redacts
            // fetcher failures behind a correlation id, but a rejected dev payload is the caller's
            // to fix, so the executor runs the pair once up front and lets the callback's exception
            // propagate verbatim. Execution's own mount then overwrites this one on the same
            // connection (mount establishes identity wholesale).
            var hookImpl = ClassName.get(schemaPackage,
                ConnectionRuntimeClassGenerator.SESSION_HOOK_IMPL_CLASS_NAME);
            var settings = ClassName.get("org.jooq.conf", "Settings");
            execute
                .addComment("Preflight: surface a rejected payload as the mount callback's own exception.")
                .addStatement("$T devDialect = $T.valueOf(dialect)", sqlDialect, sqlDialect)
                .addStatement("$T devSettings = new $T()", settings, settings);
            // A multi-tenant mount also takes the tenant it mounts for; the dev executor's one
            // connection is the default source, so it mounts for none.
            String tenantArg = multiTenant ? ", " + "java.util.Optional.empty()" : "";
            if (identity.handleType().isPresent()) {
                execute.addStatement(
                    "$T preflightHandle = $T.mount(session, connection, devDialect, devSettings$L)",
                    identity.handleType().get(), hookImpl, tenantArg);
                execute.addStatement(
                    "$T.unmount(session, connection, devDialect, devSettings$L, preflightHandle)",
                    hookImpl, tenantArg);
            } else {
                execute.addStatement("$T.mount(session, connection, devDialect, devSettings$L)",
                    hookImpl, tenantArg);
                execute.addStatement("$T.unmount(session, connection, devDialect, devSettings$L)",
                    hookImpl, tenantArg);
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
                ownedFactoryArgs(contextArgs, multiTenant, mountsIdentity),
                ClassName.get(Map.class))
            .addStatement("$T result = engine.execute(input)", executionResult)
            .addStatement("return $T.toJSONString(result.toSpecification())", jsonValue);

        var classBuilder = TypeSpec.classBuilder(CLASS_NAME)
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addJavadoc(classJavadoc())
            .addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PRIVATE).build())
            .addMethod(execute.build());
        if (!contextArgs.isEmpty()) {
            classBuilder.addMethod(contextArgHelper());
        }
        classBuilder.addType(singleConnectionDataSource(connection));
        return List.of(classBuilder.build());
    }

    /**
     * The argument list for the {@code newOwnedExecutionInput} call: one typed extraction per
     * contextArgument in the classifier's alphabetical order (the same {@code resolved()} order
     * the facade's factory parameters use, so the two cannot disagree on position). The request's
     * {@code SessionState}, when the graph supplies identity, is the executor's own parameter.
     */
    private static CodeBlock ownedFactoryArgs(List<ResolvedContextArg> contextArgs, boolean multiTenant,
            boolean mountsIdentity) {
        var args = CodeBlock.builder().add("(");
        boolean first = true;
        if (multiTenant) {
            // The dev executor runs single-connection with no tenant map, so no tenant can be
            // served: an empty request tenant set satisfies the factory slot, a fanned field
            // resolves to an empty union, and a routed field fails with the refusal rather than
            // a missing-parameter or unhosted-tenant failure.
            args.add("$T.of()", ClassName.get("java.util", "Set"));
            first = false;
        }
        if (mountsIdentity) {
            if (!first) {
                args.add(",\n        ");
            }
            args.add("session");
            first = false;
        }
        for (ResolvedContextArg arg : contextArgs) {
            if (!first) {
                args.add(",\n        ");
            }
            first = false;
            args.add("($T) $N(contextArgs, $S, $T.class)",
                arg.javaType(), CONTEXT_ARG_HELPER, arg.name(), rawType(arg.javaType()));
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

    private static String executeJavadoc(boolean mountsIdentity, List<ResolvedContextArg> contextArgs) {
        var sb = new StringBuilder();
        sb.append("Executes one GraphQL operation against the generated schema on {@code connection}\n");
        sb.append("and returns the JSON-serialized {@code ExecutionResult.toSpecification()} (data plus\n");
        sb.append("GraphQL errors; execution failures surface as GraphQL errors in the payload rather\n");
        sb.append("than exceptions).\n");
        sb.append("\n");
        sb.append("<p>Mutations run under the {@code ROLLBACK_ONLY} commit policy: each mutation\n");
        sb.append("field's transaction rolls back at settle, so writes are observable in the response\n");
        sb.append("but never persist.\n");
        if (mountsIdentity) {
            sb.append("\n");
            sb.append("<p>This schema configures {@code <sessionState>}: the request's {@code SessionState}\n");
            sb.append("mounts identity exactly as a production caller's would, and a missing one fails\n");
            sb.append("loudly rather than running unsecured. It is preflighted before execution, so a\n");
            sb.append("rejected payload propagates as the callback's own exception rather than a redacted\n");
            sb.append("execution error.\n");
        }
        sb.append("@param connection the open JDBC connection to execute on; the caller owns it, but\n");
        sb.append("release may close it (idempotent with the caller's own close)\n");
        sb.append("@param dialect the jOOQ {@code SQLDialect} name for this database, e.g. {@code POSTGRES}\n");
        sb.append("or {@code ORACLE}; must not be {@code null}\n");
        sb.append("@param query the GraphQL operation to execute; must not be {@code null}\n");
        sb.append("@param variables the operation's variables; {@code null} means none\n");
        if (mountsIdentity) {
            sb.append("@param session the request's session state, built by the dev host from the\n");
            sb.append("configured claims; must not be {@code null}\n");
        } else {
            sb.append("@param claims unused by this schema (it mounts no identity); may be {@code null}\n");
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
