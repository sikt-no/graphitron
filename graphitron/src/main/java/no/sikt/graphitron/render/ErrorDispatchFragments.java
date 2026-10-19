package no.sikt.graphitron.render;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeName;

/**
 * The {@code catch} arm of a synchronous fetcher entry point: how a throw reaches the generated
 * error router, and what the router is asked to do with it.
 *
 * <p>The primitives ({@link #redactArm}, {@link #outcomeArm}) take the generated classes they
 * name, so each emitted disposition has one spelling whoever decided it; the {@code outcome*}
 * forms name the {@code Outcome} unit's arms and the router's {@code Mapping} interface once.
 */
public final class ErrorDispatchFragments {

    private ErrorDispatchFragments() {}

    private static final ClassName DATA_FETCHER_RESULT =
        ClassName.get("graphql.execution", "DataFetcherResult");
    private static final ClassName THROWABLE = ClassName.get(Throwable.class);
    private static final ClassName LIST = ClassName.get(java.util.List.class);

    /** The nested class the generated error router declares every mapping arm under. */
    private static final String MAPPING_INTERFACE = "Mapping";

    /** The {@code Outcome} unit's error arm. */
    private static final String ERROR_LIST_CLASS = "ErrorList";

    /** The {@code Outcome} unit's success arm. */
    private static final String SUCCESS_CLASS = "Success";

    /** {@code Outcome<X>}: the value type an entry point on the {@code Outcome} transport declares. */
    public static TypeName outcomeType(ClassName outcome, TypeName valueType) {
        return ParameterizedTypeName.get(outcome, FetcherResult.boxed(valueType));
    }

    /**
     * The success return on the {@code Outcome} transport: the local wrapped in
     * {@code Outcome.Success}. {@code declaredType} is {@link #outcomeType}'s answer.
     */
    public static CodeBlock outcomeSuccessReturn(ClassName outcome, TypeName declaredType,
            String payloadLocal, TenantHandDown handDown) {
        return FetcherResult.outcomeSuccess(declaredType, outcome.nestedClass(SUCCESS_CLASS),
            payloadLocal, handDown);
    }

    /**
     * {@link #outcomeArm} over the generated runtime units: the router's {@code Mapping} interface
     * and the outcome's {@code ErrorList} arm are named here, once.
     */
    public static CodeBlock outcomeCatchArm(ClassName errorRouter, ClassName errorMappings,
            String mappingsConstantName, TypeName declaredType, ClassName outcome) {
        return outcomeArm(errorRouter.nestedClass(MAPPING_INTERFACE), errorMappings,
            mappingsConstantName, declaredType, outcome.nestedClass(ERROR_LIST_CLASS), errorRouter);
    }

    /**
     * The {@code Outcome}-wrapped arm: walks the channel's mappings in source order and, per
     * mapping, the throw's cause chain outermost-first; the first match returns
     * {@code ErrorList(List.of(cause))} as the entry point's data, which the payload's errors
     * field reads off its source. An unmatched throw falls through to {@link #redactArm}, the
     * same disposition a fetcher without a channel gets, so an unclaimed client exception
     * surfaces its own message rather than redacting. One spelling for every producer on the {@code Outcome} transport.
     */
    public static CodeBlock outcomeArm(ClassName mappingInterface, ClassName errorMappingsClass,
            String mappingsConstantName, TypeName valueType, ClassName errorListClass,
            ClassName errorRouterClass) {
        return CodeBlock.builder()
            .add("for ($T mapping : $T.$L) {\n", mappingInterface, errorMappingsClass, mappingsConstantName)
            .indent()
            .add("for ($T cause = e; cause != null; cause = cause.getCause()) {\n", THROWABLE)
            .indent()
            .add("if (mapping.match(cause)) {\n")
            .indent()
            .add("return $T.<$T>newResult().data(new $T<>($T.of(cause))).build();\n",
                DATA_FETCHER_RESULT, valueType, errorListClass, LIST)
            .unindent()
            .add("}\n")
            .unindent()
            .add("}\n")
            .unindent()
            .add("}\n")
            .add(redactArm(errorRouterClass, "e"))
            .build();
    }

    /**
     * The privacy disposition, as a bare expression over the named throwable local:
     * {@code ErrorRouter.surfaceClientErrorOrRedact(<var>, env)}. A client exception surfaces its
     * own message; every other throwable redacts to a correlation id. It is the whole arm on a
     * fetcher without a channel and the unmatched tail of {@link #outcomeArm}. Shared by the
     * synchronous catch arms and the asynchronous {@code .exceptionally} tails, which is why it
     * is an expression rather than a statement.
     */
    public static CodeBlock redact(ClassName errorRouterClass, String throwableVar) {
        return CodeBlock.of("$T.surfaceClientErrorOrRedact($L, env)", errorRouterClass, throwableVar);
    }

    /** {@link #redact} as the arm's whole {@code return} statement. */
    public static CodeBlock redactArm(ClassName errorRouterClass, String throwableVar) {
        return CodeBlock.of("return $L;\n", redact(errorRouterClass, throwableVar));
    }
}
