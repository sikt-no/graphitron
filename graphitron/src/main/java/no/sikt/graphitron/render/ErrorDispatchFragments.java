package no.sikt.graphitron.render;

import no.sikt.graphitron.command.ErrorDispatch;
import no.sikt.graphitron.command.UnitRef;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;

/**
 * The {@code catch} arm of a synchronous fetcher entry point: how a throw reaches the generated
 * error router, and what the router is asked to do with it.
 *
 * <p>Two levels, deliberately. {@link #catchArm} interprets an {@link ErrorDispatch} row, which is
 * what a renderer on the command seam holds. The two named forms below it are the primitives, and
 * the unmigrated generator hosts read those directly off the classifier's error channel, so each
 * emitted disposition has one spelling while families move across the seam one at a time.
 */
public final class ErrorDispatchFragments {

    private ErrorDispatchFragments() {}

    /** The whole {@code catch} arm for one dispatch row. */
    public static CodeBlock catchArm(ErrorDispatch errors, CodeBlock localContextSentinel) {
        return switch (errors) {
            case ErrorDispatch.Redacting r -> redactArm(className(r.errorRouter()), "e");
            case ErrorDispatch.LocalContextRouted lc -> {
                if (localContextSentinel == null) {
                    throw new IllegalStateException(
                        "a localContext-routed catch arm needs the carrier's sentinel record:"
                        + " graphql-java short-circuits a null parent's children, so the data side"
                        + " must receive a non-null all-null-column record");
                }
                yield localContextArm(className(lc.errorRouter()), className(lc.errorMappings()),
                    lc.mappingsConstantName(), localContextSentinel);
            }
        };
    }

    /**
     * The privacy disposition, as a bare expression over the named throwable local:
     * {@code ErrorRouter.surfaceClientErrorOrRedact(<var>, env)}. A client exception surfaces its
     * own message; every other throwable redacts to a correlation id. Shared by the synchronous
     * catch arms and the asynchronous {@code .exceptionally} tails, which is why it is an
     * expression rather than a statement.
     */
    public static CodeBlock redact(ClassName errorRouterClass, String throwableVar) {
        return CodeBlock.of("$T.surfaceClientErrorOrRedact($L, env)", errorRouterClass, throwableVar);
    }

    /** {@link #redact} as the arm's whole {@code return} statement. */
    public static CodeBlock redactArm(ClassName errorRouterClass, String throwableVar) {
        return CodeBlock.of("return $L;\n", redact(errorRouterClass, throwableVar));
    }

    /**
     * The localContext-routed arm: the matched throwable is placed into
     * {@code DataFetcherResult.localContext}, where the carrier's errors-field fetcher reads it,
     * and the data side receives the sentinel. No payload factory is involved, unlike the
     * payload-class arm.
     */
    public static CodeBlock localContextArm(ClassName errorRouterClass, ClassName errorMappingsClass,
            String mappingsConstantName, CodeBlock sentinel) {
        return CodeBlock.of("return $T.dispatchToLocalContext(e, $T.$L, env, $L);\n",
            errorRouterClass, errorMappingsClass, mappingsConstantName, sentinel);
    }

    private static ClassName className(UnitRef unit) {
        return ClassName.get(unit.packageName(), unit.simpleName());
    }
}
