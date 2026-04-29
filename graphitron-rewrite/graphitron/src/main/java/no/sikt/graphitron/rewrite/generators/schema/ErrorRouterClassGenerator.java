package no.sikt.graphitron.rewrite.generators.schema;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.javapoet.TypeVariableName;

import javax.lang.model.element.Modifier;
import java.util.List;
import java.util.UUID;

/**
 * Generates the {@code ErrorRouter} class emitted at
 * {@code <outputPackage>.schema.ErrorRouter}, once per code-generation run.
 *
 * <p>Provides the runtime entry points the C3 fetcher try/catch wrappers call:
 *
 * <ul>
 *   <li>{@code redact(Throwable, DataFetchingEnvironment)}: no-channel disposition.
 *       Logs the original throw at ERROR with a fresh UUID correlation ID; returns a
 *       {@code DataFetcherResult} with {@code data=null} and a single redacted error
 *       carrying only the correlation ID. The raw exception message is never put into
 *       the response (privacy contract).</li>
 *   <li>{@code dispatch(...)}: channel-mapped dispatch. Lands in C3g alongside
 *       {@code ErrorMappings} and the sealed {@code Mapping} taxonomy that mirrors
 *       {@link no.sikt.graphitron.rewrite.model.GraphitronType.ErrorType.Handler}.</li>
 * </ul>
 *
 * <p>Spec: {@code error-handling-parity.md} §3, "Drop the custom ExecutionStrategy.
 * Wrap try/catch at the fetcher".
 */
public final class ErrorRouterClassGenerator {

    public static final String CLASS_NAME = "ErrorRouter";

    private static final ClassName THROWABLE                  = ClassName.get(Throwable.class);
    private static final ClassName DATA_FETCHING_ENVIRONMENT  = ClassName.get("graphql.schema", "DataFetchingEnvironment");
    private static final ClassName DATA_FETCHER_RESULT        = ClassName.get("graphql.execution", "DataFetcherResult");
    private static final ClassName GRAPHQL_ERROR_BUILDER      = ClassName.get("graphql", "GraphqlErrorBuilder");
    private static final ClassName UUID_CN                    = ClassName.get(UUID.class);
    private static final ClassName LOGGER_CN                  = ClassName.get("org.slf4j", "Logger");
    private static final ClassName LOGGER_FACTORY_CN          = ClassName.get("org.slf4j", "LoggerFactory");

    private ErrorRouterClassGenerator() {}

    public static List<TypeSpec> generate() {
        var typeP = TypeVariableName.get("P");
        var resultOfP = ParameterizedTypeName.get(DATA_FETCHER_RESULT, typeP);

        // private static final Logger LOGGER = LoggerFactory.getLogger(ErrorRouter.class);
        var loggerField = no.sikt.graphitron.javapoet.FieldSpec.builder(LOGGER_CN, "LOGGER",
                Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
            .initializer("$T.getLogger($L.class)", LOGGER_FACTORY_CN, CLASS_NAME)
            .build();

        var redact = MethodSpec.methodBuilder("redact")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addTypeVariable(typeP)
            .returns(resultOfP)
            .addParameter(THROWABLE, "thrown")
            .addParameter(DATA_FETCHING_ENVIRONMENT, "env")
            .addJavadoc("No-channel disposition: logs the original throw at ERROR with a fresh\n"
                + "UUID correlation ID and returns a {@link $T} with {@code data=null} plus a\n"
                + "single redacted error carrying only the correlation ID. Generic on {@code P},\n"
                + "the field's data type; the call site infers {@code P} from its target type so\n"
                + "the catch arm fits the fetcher's declared return type without a witness.\n"
                + "\n"
                + "<p>The raw exception message is never put into the response. This is the\n"
                + "privacy property the rewrite preserves at the fetcher catch site (see\n"
                + "{@code error-handling-parity.md} §3 \"No top-level handler\").\n",
                DATA_FETCHER_RESULT)
            .addCode(redactBody())
            .build();

        var spec = TypeSpec.classBuilder(CLASS_NAME)
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addJavadoc("Runtime entry points for the per-fetcher exception-handling wrappers emitted\n"
                + "by Graphitron. Every emitted fetcher wraps its body in a try/catch (and an\n"
                + "{@code .exceptionally} arm for async fetchers) that funnels thrown exceptions\n"
                + "through this class: either to a channel-mapped typed payload\n"
                + "({@code dispatch}, lands with {@code ErrorMappings} in C3g) or, in the\n"
                + "no-channel case, to a redacted {@code DataFetcherResult} carrying only a\n"
                + "correlation ID ({@code redact}).\n"
                + "\n"
                + "<p>Generated alongside {@code GraphitronError} and\n"
                + "{@code ValidationViolationGraphQLException}; preserves the rewrite's\n"
                + "no-runtime-jar invariant.\n")
            .addField(loggerField)
            .addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PRIVATE).build())
            .addMethod(redact)
            .build();

        return List.of(spec);
    }

    private static no.sikt.graphitron.javapoet.CodeBlock redactBody() {
        return no.sikt.graphitron.javapoet.CodeBlock.builder()
            .addStatement("$T correlationId = $T.randomUUID()", UUID_CN, UUID_CN)
            .addStatement("LOGGER.error(\"Unmatched exception in fetcher; correlation id = {}\", correlationId, thrown)")
            .add("return $T.<P>newResult()\n", DATA_FETCHER_RESULT)
            .add("    .data(null)\n")
            .add("    .error($T.newError(env)\n", GRAPHQL_ERROR_BUILDER)
            .add("        .message(\"An error occurred. Reference: \" + correlationId + \".\")\n")
            .add("        .build())\n")
            .addStatement("    .build()")
            .build();
    }
}
