package no.sikt.graphitron.render;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;

/**
 * The {@code catch} arm of a synchronous fetcher entry point: how a throw reaches the generated
 * error router, and what the router is asked to do with it.
 *
 * <p>The primitives ({@link #redactArm}, {@link #outcomeArm}) take the generated classes they
 * name, so each emitted disposition has one spelling whoever decided it; the {@code outcome*}
 * forms name the {@code Outcome} unit's arms once.
 */
public final class ErrorDispatchFragments {

    private ErrorDispatchFragments() {}

    private static final ClassName DATA_FETCHER_RESULT =
        ClassName.get("graphql.execution", "DataFetcherResult");
    private static final ClassName THROWABLE = ClassName.get(Throwable.class);
    private static final ClassName LIST = ClassName.get(java.util.List.class);

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
     * {@link #outcomeArm} over the generated runtime units: the outcome's {@code ErrorList} arm is
     * named here, once.
     */
    public static CodeBlock outcomeCatchArm(ClassName errorRouter, ClassName errorMappings,
            String mappingsConstantName, TypeName declaredType, ClassName outcome) {
        return outcomeCatchArm(errorRouter, errorMappings, mappingsConstantName, declaredType, outcome, "e");
    }

    /**
     * {@link #outcomeCatchArm} over the throwable local named {@code throwableVar}, for an arm that
     * is not a {@code catch (Throwable e)} block's: an asynchronous {@code .exceptionally} lambda,
     * whose parameter must not shadow a {@code catch} parameter it may be nested in.
     */
    public static CodeBlock outcomeCatchArm(ClassName errorRouter, ClassName errorMappings,
            String mappingsConstantName, TypeName declaredType, ClassName outcome, String throwableVar) {
        return outcomeArm(errorMappings, mappingsConstantName, declaredType,
            outcome.nestedClass(ERROR_LIST_CLASS), errorRouter, throwableVar);
    }

    /**
     * The {@code Outcome}-wrapped arm: asks {@code ErrorRouter.claim} for the first link of the
     * throw's cause chain the channel's mappings claim, in source order; a claimed cause returns
     * {@code ErrorList(List.of(cause))} as the entry point's data, which the payload's errors
     * field reads off its source. An unmatched throw falls through to {@link #redactArm}, the
     * same disposition a fetcher without a channel gets, so an unclaimed client exception
     * surfaces its own message rather than redacting. One spelling for every producer on the {@code Outcome} transport.
     */
    public static CodeBlock outcomeArm(ClassName errorMappingsClass,
            String mappingsConstantName, TypeName valueType, ClassName errorListClass,
            ClassName errorRouterClass) {
        return outcomeArm(errorMappingsClass, mappingsConstantName, valueType, errorListClass,
            errorRouterClass, "e");
    }

    /** {@link #outcomeArm} over the throwable local named {@code throwableVar}. */
    public static CodeBlock outcomeArm(ClassName errorMappingsClass,
            String mappingsConstantName, TypeName valueType, ClassName errorListClass,
            ClassName errorRouterClass, String throwableVar) {
        return CodeBlock.builder()
            .addStatement("$T claimed = $T.claim($L, $T.$L, env)",
                THROWABLE, errorRouterClass, throwableVar, errorMappingsClass, mappingsConstantName)
            .add("if (claimed != null) {\n")
            .indent()
            .add("return $T.<$T>newResult().data(new $T<>($T.of(claimed))).build();\n",
                DATA_FETCHER_RESULT, valueType, errorListClass, LIST)
            .unindent()
            .add("}\n")
            .add(redactArm(errorRouterClass, throwableVar))
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
