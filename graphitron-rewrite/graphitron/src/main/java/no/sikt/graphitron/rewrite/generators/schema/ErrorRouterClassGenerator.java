package no.sikt.graphitron.rewrite.generators.schema;

import no.sikt.graphitron.javapoet.ArrayTypeName;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.FieldSpec;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.javapoet.TypeVariableName;
import no.sikt.graphitron.javapoet.WildcardTypeName;

import javax.lang.model.element.Modifier;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/**
 * Generates the {@code ErrorRouter} class emitted at
 * {@code <outputPackage>.schema.ErrorRouter}, once per code-generation run.
 *
 * <p>Provides the runtime entry points the per-fetcher try/catch wrappers call:
 *
 * <ul>
 *   <li>{@code redact(Throwable, DataFetchingEnvironment)}: no-channel disposition.
 *       Logs the original throw at ERROR with a fresh UUID correlation ID; returns a
 *       {@code DataFetcherResult} with {@code data=null} and a single redacted error
 *       carrying only the correlation ID. The raw exception message is never put into
 *       the response (privacy contract).</li>
 *   <li>{@code dispatch(Throwable, Mapping[], DataFetchingEnvironment, Function)}:
 *       channel-mapped dispatch. Walks the cause chain outermost-first per mapping and
 *       places the matched source object directly into the errors list, then constructs
 *       the payload via the per-fetcher synthesized {@code payloadFactory}. Falls
 *       through to {@code redact} on no match. Carries a
 *       {@code ValidationViolationGraphQLException} fan-out arm that places each
 *       carried {@code GraphQLError} directly into the errors list; that arm and the
 *       carrier exception both retire when the native-Jakarta-validation chunk lands.</li>
 *   <li>The nested {@code Mapping} taxonomy ({@code ExceptionMapping},
 *       {@code SqlStateMapping}, {@code VendorCodeMapping}, {@code ValidationMapping})
 *       carries the criteria the dispatch arm matches against plus the {@code @error}
 *       handler's optional description. There is no per-mapping factory: the matched
 *       exception itself goes into the errors list, and graphql-java's
 *       {@code PropertyDataFetcher} reads each declared {@code @error} field directly
 *       off whatever source object happens to be in the slot. Each per-channel
 *       {@code Mapping[]} constant lives on the per-package {@code ErrorMappings}
 *       helper.</li>
 * </ul>
 *
 * <p>Spec: {@code error-handling-parity.md} §3, "Drop the custom ExecutionStrategy.
 * Wrap try/catch at the fetcher", and §2c "{@code @error} is TypeResolver wiring (no
 * developer-supplied data class)" for the source-direct contract.
 */
public final class ErrorRouterClassGenerator {

    public static final String CLASS_NAME = "ErrorRouter";
    public static final String MAPPING_INTERFACE = "Mapping";
    public static final String EXCEPTION_MAPPING = "ExceptionMapping";
    public static final String SQL_STATE_MAPPING = "SqlStateMapping";
    public static final String VENDOR_CODE_MAPPING = "VendorCodeMapping";
    public static final String VALIDATION_MAPPING = "ValidationMapping";

    private static final ClassName THROWABLE                  = ClassName.get(Throwable.class);
    private static final ClassName SQL_EXCEPTION              = ClassName.get("java.sql", "SQLException");
    private static final ClassName DATA_FETCHING_ENVIRONMENT  = ClassName.get("graphql.schema", "DataFetchingEnvironment");
    private static final ClassName DATA_FETCHER_RESULT        = ClassName.get("graphql.execution", "DataFetcherResult");
    private static final ClassName GRAPHQL_ERROR              = ClassName.get("graphql", "GraphQLError");
    private static final ClassName GRAPHQL_ERROR_BUILDER      = ClassName.get("graphql", "GraphqlErrorBuilder");
    private static final ClassName UUID_CN                    = ClassName.get(UUID.class);
    private static final ClassName LOGGER_CN                  = ClassName.get("org.slf4j", "Logger");
    private static final ClassName LOGGER_FACTORY_CN          = ClassName.get("org.slf4j", "LoggerFactory");
    private static final ClassName ARRAY_LIST                 = ClassName.get("java.util", "ArrayList");
    private static final ClassName LIST_CN                    = ClassName.get(List.class);
    private static final ClassName STRING_CN                  = ClassName.get(String.class);
    private static final ClassName OBJECT_CN                  = ClassName.get(Object.class);

    private ErrorRouterClassGenerator() {}

    public static List<TypeSpec> generate(String outputPackage) {
        var schemaPackage = outputPackage.isEmpty() ? "" : outputPackage + ".schema";
        var validationViolation = ClassName.get(schemaPackage, "ValidationViolationGraphQLException");

        var typeP = TypeVariableName.get("P");
        var resultOfP = ParameterizedTypeName.get(DATA_FETCHER_RESULT, typeP);

        // Mapping interface: match(throwable), description(). The build() factory and the
        // per-mapping (List<String>, String) -> Object closure are gone (R12 source-direct
        // dispatch); the matched source object is placed directly into the errors list.
        var mappingInterface = buildMappingInterface();

        // Concrete Mapping classes.
        var exceptionMapping = buildExceptionMapping();
        var sqlStateMapping = buildSqlStateMapping();
        var vendorCodeMapping = buildVendorCodeMapping();
        var validationMapping = buildValidationMapping();

        // private static final Logger LOGGER = LoggerFactory.getLogger(ErrorRouter.class);
        var loggerField = FieldSpec.builder(LOGGER_CN, "LOGGER",
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

        var dispatch = buildDispatchMethod(typeP, resultOfP, validationViolation);

        var spec = TypeSpec.classBuilder(CLASS_NAME)
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addJavadoc("Runtime entry points for the per-fetcher exception-handling wrappers emitted\n"
                + "by Graphitron. Every emitted fetcher wraps its body in a try/catch (and an\n"
                + "{@code .exceptionally} arm for async fetchers) that funnels thrown exceptions\n"
                + "through this class: either to a channel-mapped typed payload\n"
                + "({@code dispatch}, with a {@code Mapping[]} constant from {@code ErrorMappings})\n"
                + "or, in the no-channel case, to a redacted {@code DataFetcherResult} carrying\n"
                + "only a correlation ID ({@code redact}).\n"
                + "\n"
                + "<p>Generated alongside {@code ValidationViolationGraphQLException} and\n"
                + "{@code ErrorMappings}; preserves the rewrite's no-runtime-jar invariant.\n")
            .addField(loggerField)
            .addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PRIVATE).build())
            .addType(mappingInterface)
            .addType(exceptionMapping)
            .addType(sqlStateMapping)
            .addType(vendorCodeMapping)
            .addType(validationMapping)
            .addMethod(redact)
            .addMethod(dispatch)
            .build();

        return List.of(spec);
    }

    /** Backwards-compatible no-arg overload for tests that don't care about cross-class refs. */
    public static List<TypeSpec> generate() {
        return generate("");
    }

    private static TypeSpec buildMappingInterface() {
        var match = MethodSpec.methodBuilder("match")
            .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
            .returns(boolean.class)
            .addParameter(THROWABLE, "throwable")
            .addJavadoc("Whether this mapping's criteria fire on {@code throwable}. The dispatch arm\n"
                + "walks the cause chain outermost-first per mapping; the first match wins per\n"
                + "{@code error-handling-parity.md} §3.\n"
                + "\n"
                + "<p>{@link ValidationMapping#match(Throwable)} always returns {@code false} :\n"
                + "validation runs ahead of {@code MAPPINGS} iteration, not as part of it.\n")
            .build();

        var description = MethodSpec.methodBuilder("description")
            .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
            .returns(STRING_CN)
            .addJavadoc("The {@code description} from the {@code @error} declaration, or {@code null}\n"
                + "when none was set. Future emitter passes (R12 source-direct dispatch follow-on)\n"
                + "will wrap the matched source in a description-overriding facade so graphql-java's\n"
                + "{@code PropertyDataFetcher} reads the override at the SDL {@code message} field.\n")
            .build();

        return TypeSpec.interfaceBuilder(MAPPING_INTERFACE)
            .addModifiers(Modifier.PUBLIC)
            .addJavadoc("One channel-flattened entry on the per-fetcher {@code Mapping[]} array. The\n"
                + "criteria-bearing variants ({@link ExceptionMapping}, {@link SqlStateMapping},\n"
                + "{@link VendorCodeMapping}) implement {@link #match(Throwable)} against the\n"
                + "thrown cause; {@link ValidationMapping} is special-cased ahead of source-order\n"
                + "iteration. No {@code build} factory: under the source-direct dispatch contract\n"
                + "(R12 §2c, §3) the matched throwable itself goes into the errors list, and\n"
                + "graphql-java's {@code PropertyDataFetcher} reads each declared {@code @error}\n"
                + "field directly from the source.\n")
            .addMethod(match)
            .addMethod(description)
            .build();
    }

    private static TypeSpec buildExceptionMapping() {
        var classOfThrowable = ParameterizedTypeName.get(
            ClassName.get(Class.class), WildcardTypeName.subtypeOf(THROWABLE));

        return TypeSpec.classBuilder(EXCEPTION_MAPPING)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
            .addSuperinterface(ClassName.get("", MAPPING_INTERFACE))
            .addJavadoc("Matches by exception class identity. Lift target for\n"
                + "{@code @error} {@code GENERIC} handlers and the no-discriminator {@code DATABASE}\n"
                + "handler (which lifts to {@code ExceptionMapping(SQLException, ...)}).\n")
            .addField(FieldSpec.builder(classOfThrowable, "exceptionClass",
                Modifier.PRIVATE, Modifier.FINAL).build())
            .addField(FieldSpec.builder(STRING_CN, "matches",
                Modifier.PRIVATE, Modifier.FINAL).build())
            .addField(FieldSpec.builder(STRING_CN, "description",
                Modifier.PRIVATE, Modifier.FINAL).build())
            .addMethod(MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addParameter(classOfThrowable, "exceptionClass")
                .addParameter(STRING_CN, "matches")
                .addParameter(STRING_CN, "description")
                .addStatement("this.exceptionClass = exceptionClass")
                .addStatement("this.matches = matches")
                .addStatement("this.description = description")
                .build())
            .addMethod(MethodSpec.methodBuilder("match")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(boolean.class)
                .addParameter(THROWABLE, "throwable")
                .addCode("if (!exceptionClass.isInstance(throwable)) return false;\n")
                .addCode("if (matches == null) return true;\n")
                .addCode("$T msg = throwable.getMessage();\n", STRING_CN)
                .addCode("return msg != null && msg.contains(matches);\n")
                .build())
            .addMethod(MethodSpec.methodBuilder("description")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(STRING_CN)
                .addStatement("return description")
                .build())
            .build();
    }

    private static TypeSpec buildSqlStateMapping() {
        return TypeSpec.classBuilder(SQL_STATE_MAPPING)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
            .addSuperinterface(ClassName.get("", MAPPING_INTERFACE))
            .addJavadoc("Matches any {@link $T} in the cause chain whose {@code getSQLState()} equals\n"
                + "the configured {@code sqlState}. Lift target for\n"
                + "{@code @error} {@code DATABASE} handlers with {@code sqlState} set.\n", SQL_EXCEPTION)
            .addField(FieldSpec.builder(STRING_CN, "sqlState",
                Modifier.PRIVATE, Modifier.FINAL).build())
            .addField(FieldSpec.builder(STRING_CN, "matches",
                Modifier.PRIVATE, Modifier.FINAL).build())
            .addField(FieldSpec.builder(STRING_CN, "description",
                Modifier.PRIVATE, Modifier.FINAL).build())
            .addMethod(MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addParameter(STRING_CN, "sqlState")
                .addParameter(STRING_CN, "matches")
                .addParameter(STRING_CN, "description")
                .addStatement("this.sqlState = sqlState")
                .addStatement("this.matches = matches")
                .addStatement("this.description = description")
                .build())
            .addMethod(MethodSpec.methodBuilder("match")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(boolean.class)
                .addParameter(THROWABLE, "throwable")
                .addCode("if (!(throwable instanceof $T sqlEx)) return false;\n", SQL_EXCEPTION)
                .addCode("if (!sqlState.equals(sqlEx.getSQLState())) return false;\n")
                .addCode("if (matches == null) return true;\n")
                .addCode("$T msg = throwable.getMessage();\n", STRING_CN)
                .addCode("return msg != null && msg.contains(matches);\n")
                .build())
            .addMethod(MethodSpec.methodBuilder("description")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(STRING_CN)
                .addStatement("return description")
                .build())
            .build();
    }

    private static TypeSpec buildVendorCodeMapping() {
        return TypeSpec.classBuilder(VENDOR_CODE_MAPPING)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
            .addSuperinterface(ClassName.get("", MAPPING_INTERFACE))
            .addJavadoc("Matches any {@link $T} in the cause chain whose {@code getErrorCode()}\n"
                + "string-equals the configured {@code vendorCode}. Lift target for\n"
                + "{@code @error} {@code DATABASE} handlers with {@code code} set.\n", SQL_EXCEPTION)
            .addField(FieldSpec.builder(STRING_CN, "vendorCode",
                Modifier.PRIVATE, Modifier.FINAL).build())
            .addField(FieldSpec.builder(STRING_CN, "matches",
                Modifier.PRIVATE, Modifier.FINAL).build())
            .addField(FieldSpec.builder(STRING_CN, "description",
                Modifier.PRIVATE, Modifier.FINAL).build())
            .addMethod(MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addParameter(STRING_CN, "vendorCode")
                .addParameter(STRING_CN, "matches")
                .addParameter(STRING_CN, "description")
                .addStatement("this.vendorCode = vendorCode")
                .addStatement("this.matches = matches")
                .addStatement("this.description = description")
                .build())
            .addMethod(MethodSpec.methodBuilder("match")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(boolean.class)
                .addParameter(THROWABLE, "throwable")
                .addCode("if (!(throwable instanceof $T sqlEx)) return false;\n", SQL_EXCEPTION)
                .addCode("if (!vendorCode.equals($T.valueOf(sqlEx.getErrorCode()))) return false;\n", STRING_CN)
                .addCode("if (matches == null) return true;\n")
                .addCode("$T msg = throwable.getMessage();\n", STRING_CN)
                .addCode("return msg != null && msg.contains(matches);\n")
                .build())
            .addMethod(MethodSpec.methodBuilder("description")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(STRING_CN)
                .addStatement("return description")
                .build())
            .build();
    }

    private static TypeSpec buildValidationMapping() {
        return TypeSpec.classBuilder(VALIDATION_MAPPING)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
            .addSuperinterface(ClassName.get("", MAPPING_INTERFACE))
            .addJavadoc("Marker for {@code ValidationViolationGraphQLException} fan-out: the dispatch\n"
                + "arm checks for that exception ahead of source-order iteration and places each\n"
                + "carried {@code GraphQLError} directly into the errors list.\n"
                + "{@link #match} always returns {@code false} so this entry is skipped during the\n"
                + "regular cause-chain match loop.\n"
                + "\n"
                + "<p>Retires together with {@code ValidationViolationGraphQLException} when the\n"
                + "native-Jakarta-validation chunk lands; validation moves into a wrapper pre-step\n"
                + "and the dispatcher loses the validation arm entirely.\n")
            .addField(FieldSpec.builder(STRING_CN, "description",
                Modifier.PRIVATE, Modifier.FINAL).build())
            .addMethod(MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addParameter(STRING_CN, "description")
                .addStatement("this.description = description")
                .build())
            .addMethod(MethodSpec.methodBuilder("match")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(boolean.class)
                .addParameter(THROWABLE, "throwable")
                .addStatement("return false")
                .build())
            .addMethod(MethodSpec.methodBuilder("description")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(STRING_CN)
                .addStatement("return description")
                .build())
            .build();
    }

    private static MethodSpec buildDispatchMethod(
            TypeVariableName typeP,
            ParameterizedTypeName resultOfP,
            ClassName validationViolation) {
        var mapping = ClassName.get("", MAPPING_INTERFACE);
        var validationMapping = ClassName.get("", VALIDATION_MAPPING);
        var mappingArray = ArrayTypeName.of(mapping);
        var listOfWildcard = ParameterizedTypeName.get(LIST_CN, WildcardTypeName.subtypeOf(OBJECT_CN));
        var payloadFactoryType = ParameterizedTypeName.get(ClassName.get(Function.class),
            listOfWildcard, typeP);
        var listErrors = ParameterizedTypeName.get(LIST_CN, (TypeName) GRAPHQL_ERROR);

        return MethodSpec.methodBuilder("dispatch")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addTypeVariable(typeP)
            .returns(resultOfP)
            .addParameter(THROWABLE, "thrown")
            .addParameter(mappingArray, "mappings")
            .addParameter(DATA_FETCHING_ENVIRONMENT, "env")
            .addParameter(payloadFactoryType, "payloadFactory")
            .addJavadoc("Channel-mapped dispatch (R12 source-direct contract). Walks the cause chain\n"
                + "twice:\n"
                + "<ol>\n"
                + "  <li>Validation arm: if any cause is a\n"
                + "      {@link $T}, place its carried {@code GraphQLError}s directly into the\n"
                + "      errors list and return.</li>\n"
                + "  <li>Source-order match: for each non-{@link ValidationMapping} mapping in\n"
                + "      declaration order, walk the cause chain outermost-first; the first match\n"
                + "      places the matched {@code Throwable} itself into the errors list and\n"
                + "      returns the populated payload.</li>\n"
                + "</ol>\n"
                + "Falls through to {@link #redact} on no match. graphql-java's\n"
                + "{@code PropertyDataFetcher} reads each declared {@code @error} field directly\n"
                + "from whatever source object happens to be in the slot; per-channel\n"
                + "{@code TypeResolver}s (R12 §2c) dispatch each source to its SDL {@code @error}\n"
                + "type at serialisation time.\n"
                + "\n"
                + "<p>The {@code description} field on each {@link Mapping} is currently unused at\n"
                + "the dispatch site; the description-overriding facade is a follow-on emitter\n"
                + "concern (R12 §3 \"Concrete dispatch signature\", final paragraph).\n",
                validationViolation)
            // ----- Validation arm -----
            .addCode("// Validation arm: scan the cause chain for ValidationViolationGraphQLException.\n")
            .addStatement("$T validationCause = null", validationViolation)
            .beginControlFlow("for ($T t = thrown; t != null; t = t.getCause())", THROWABLE)
            .addCode("if (t instanceof $T vve) { validationCause = vve; break; }\n", validationViolation)
            .endControlFlow()
            .beginControlFlow("if (validationCause != null)")
            .addStatement("$T validationErrors = new $T<>()", listErrors, ARRAY_LIST)
            .beginControlFlow("for ($T err : validationCause.getUnderlyingErrors())", GRAPHQL_ERROR)
            .addStatement("validationErrors.add(err)")
            .endControlFlow()
            .addStatement("return $T.<P>newResult().data(payloadFactory.apply(validationErrors)).build()",
                DATA_FETCHER_RESULT)
            .endControlFlow()
            // ----- Source-order match -----
            .addCode("\n// Source-order match: first (mapping, cause) pair that satisfies the predicate.\n")
            .beginControlFlow("for ($T mapping : mappings)", mapping)
            .addCode("if (mapping instanceof $T) continue;\n", validationMapping)
            .beginControlFlow("for ($T t = thrown; t != null; t = t.getCause())", THROWABLE)
            .beginControlFlow("if (mapping.match(t))")
            .addStatement("return $T.<P>newResult().data(payloadFactory.apply($T.of(t))).build()",
                DATA_FETCHER_RESULT, LIST_CN)
            .endControlFlow()
            .endControlFlow()
            .endControlFlow()
            // ----- Unmatched: redact -----
            .addCode("\n// Unmatched: log + redacted DataFetcherResult.\n")
            .addStatement("return redact(thrown, env)")
            .build();
    }

    private static CodeBlock redactBody() {
        return CodeBlock.builder()
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
