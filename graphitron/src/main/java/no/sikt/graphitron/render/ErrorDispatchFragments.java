package no.sikt.graphitron.render;

import no.sikt.graphitron.command.ErrorDispatch;
import no.sikt.graphitron.command.UnitRef;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeName;

/**
 * The {@code catch} arm of a synchronous fetcher entry point: how a throw reaches the generated
 * error router, and what the router is asked to do with it.
 *
 * <p>Two levels, deliberately. {@link #catchArm} interprets an {@link ErrorDispatch} row, which is
 * what a renderer on the command seam holds. The named forms below it ({@link #redactArm},
 * {@link #outcomeArm}) are the primitives, and the unmigrated generator hosts read those directly
 * off the classifier's error channel, so each emitted disposition has one spelling while families
 * move across the seam one at a time.
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

    /**
     * The value type an entry point declares under {@code errors}: {@code Outcome<X>} on the
     * {@link ErrorDispatch.OutcomeWrapped} arm, {@code valueType} itself on the redacting one.
     */
    public static TypeName declaredValueType(ErrorDispatch errors, TypeName valueType) {
        return errors instanceof ErrorDispatch.OutcomeWrapped o
            ? ParameterizedTypeName.get(className(o.outcome()), FetcherResult.boxed(valueType))
            : valueType;
    }

    /**
     * The success return under {@code errors}: the local wrapped in {@code Outcome.Success} on the
     * {@link ErrorDispatch.OutcomeWrapped} arm, returned bare on the redacting one.
     * {@code declaredType} is {@link #declaredValueType}'s answer for the same row.
     */
    public static CodeBlock successReturn(ErrorDispatch errors, TypeName declaredType,
            String payloadLocal, TenantHandDown handDown) {
        return errors instanceof ErrorDispatch.OutcomeWrapped o
            ? FetcherResult.outcomeSuccess(declaredType,
                className(o.outcome()).nestedClass(SUCCESS_CLASS), payloadLocal, handDown)
            : FetcherResult.success(declaredType, payloadLocal, handDown);
    }

    /**
     * The whole {@code catch} arm for one dispatch row. {@code valueType} is the entry point's
     * declared {@code DataFetcherResult} value type: the {@code Outcome<X>} parameterisation on
     * the {@link ErrorDispatch.OutcomeWrapped} arm, unread on the redacting one.
     */
    public static CodeBlock catchArm(ErrorDispatch errors, TypeName valueType) {
        return switch (errors) {
            case ErrorDispatch.Redacting r -> redactArm(className(r.errorRouter()), "e");
            case ErrorDispatch.OutcomeWrapped o -> {
                var router = className(o.errorRouter());
                yield outcomeArm(router.nestedClass(MAPPING_INTERFACE), className(o.errorMappings()),
                    o.mappingsConstantName(), valueType,
                    className(o.outcome()).nestedClass(ERROR_LIST_CLASS), router);
            }
        };
    }

    /**
     * The {@code Outcome}-wrapped arm: walks the channel's mappings in source order and, per
     * mapping, the throw's cause chain outermost-first; the first match returns
     * {@code ErrorList(List.of(cause))} as the entry point's data, which the payload's errors
     * field reads off its source. An unmatched throw falls through to {@link #redactArm}, the
     * same disposition a fetcher without a channel gets, so an unclaimed client exception
     * surfaces its own message rather than redacting. One spelling for every producer on the {@code Outcome} transport: the
     * command-seam renderers reach it through {@link #catchArm}, the unmigrated generator hosts
     * call it directly.
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

    private static ClassName className(UnitRef unit) {
        return ClassName.get(unit.packageName(), unit.simpleName());
    }
}
