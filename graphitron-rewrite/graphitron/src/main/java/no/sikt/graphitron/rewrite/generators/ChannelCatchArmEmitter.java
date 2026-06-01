package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.rewrite.generators.schema.ErrorMappingsClassGenerator;
import no.sikt.graphitron.rewrite.generators.schema.ErrorRouterClassGenerator;
import no.sikt.graphitron.rewrite.generators.schema.OutcomeClassGenerator;
import no.sikt.graphitron.rewrite.model.ErrorChannel;

import java.util.List;
import java.util.Optional;

/**
 * Emits the body of a synchronous fetcher's {@code catch (Throwable e)} arm for an
 * {@link no.sikt.graphitron.rewrite.model.WithErrorChannel} field (R244). One seam serves all three
 * transports so consumers dispatch on the optional carrier rather than branching across parallel
 * {@code catchArm} overloads:
 *
 * <ul>
 *   <li>{@code Optional.empty()} : {@code return ErrorRouter.redact(e, env);} (redact-only).</li>
 *   <li>{@link ErrorChannel.Mapped} : the inline mapping-walk loop that, on the first matching
 *       {@code (Mapping, cause)}, returns {@code DataFetcherResult.<P>newResult()
 *       .data(new Outcome.ErrorList<>(List.of(t))).build()}, falling through to
 *       {@code ErrorRouter.redact} on no match. {@code P} is the {@code valueType} the caller
 *       passes (the {@code Outcome<X>} parameterisation).</li>
 *   <li>{@link ErrorChannel.LocalContext} : the DML sentinel arm,
 *       {@code return ErrorRouter.dispatchToLocalContext(e, ErrorMappings.<CONST>, env, sentinel);}.
 *       Reachable only for DML fields; the classifier guarantees in-scope fields never carry
 *       {@code LocalContext}. Requires a non-null {@code localContextSentinel}.</li>
 * </ul>
 *
 * <p>The thrown value is the local {@code e} (matching the existing fetcher try/catch discipline);
 * the loop's cause-walk binds {@code __t} and the mapping {@code __m} to avoid colliding with
 * author-visible names. Scoped to the synchronous catch arm in slice-1 commit 2; the async
 * {@code .exceptionally(...)} tail is rewired to this carrier at the in-scope flip.
 */
public final class ChannelCatchArmEmitter {

    private ChannelCatchArmEmitter() {}

    public static CodeBlock emit(
            Optional<ErrorChannel> channel,
            TypeName valueType,
            String outputPackage,
            CodeBlock localContextSentinel) {
        if (channel.isEmpty()) {
            return CodeBlock.of("return $T.redact(e, env);\n", errorRouterClass(outputPackage));
        }
        return switch (channel.get()) {
            case ErrorChannel.Mapped m -> mappedCatchArm(m, valueType, outputPackage);
            case ErrorChannel.LocalContext lc -> {
                if (localContextSentinel == null) {
                    throw new IllegalStateException(
                        "ChannelCatchArmEmitter reached ErrorChannel.LocalContext without a sentinel "
                        + "source; every caller that may produce a LocalContext channel must pass one");
                }
                yield CodeBlock.of("return $T.dispatchToLocalContext(e, $T.$L, env, $L);\n",
                    errorRouterClass(outputPackage),
                    errorMappingsClass(outputPackage),
                    lc.mappingsConstantName(),
                    localContextSentinel);
            }
            // PayloadClass is retired by the in-scope flip and never reaches this seam (the legacy
            // dispatchCatchArm still owns it during the additive window); not a case here because
            // ChannelCatchArmEmitter is the wrapper-transport seam only.
            case ErrorChannel.PayloadClass pc -> throw new IllegalStateException(
                "ChannelCatchArmEmitter does not emit ErrorChannel.PayloadClass; that arm routes "
                + "through the legacy dispatchCatchArm until it is deleted in slice-1 commit 4");
        };
    }

    private static CodeBlock mappedCatchArm(
            ErrorChannel.Mapped channel, TypeName valueType, String outputPackage) {
        return CodeBlock.builder()
            .add("for ($T mapping : $T.$L) {\n",
                mappingInterface(outputPackage), errorMappingsClass(outputPackage),
                channel.mappingsConstantName())
            .indent()
            .add("for ($T cause = e; cause != null; cause = cause.getCause()) {\n", ClassName.get(Throwable.class))
            .indent()
            .add("if (mapping.match(cause)) {\n")
            .indent()
            .add("return $T.<$T>newResult().data(new $T<>($T.of(cause))).build();\n",
                dataFetcherResult(), valueType, errorListClass(outputPackage),
                ClassName.get(List.class))
            .unindent()
            .add("}\n")
            .unindent()
            .add("}\n")
            .unindent()
            .add("}\n")
            .add("return $T.redact(e, env);\n", errorRouterClass(outputPackage))
            .build();
    }

    private static ClassName dataFetcherResult() {
        return ClassName.get("graphql.execution", "DataFetcherResult");
    }

    private static ClassName errorRouterClass(String outputPackage) {
        return ClassName.get(outputPackage + ".schema", ErrorRouterClassGenerator.CLASS_NAME);
    }

    private static ClassName mappingInterface(String outputPackage) {
        return errorRouterClass(outputPackage).nestedClass(ErrorRouterClassGenerator.MAPPING_INTERFACE);
    }

    private static ClassName errorMappingsClass(String outputPackage) {
        return ClassName.get(outputPackage + ".schema", ErrorMappingsClassGenerator.CLASS_NAME);
    }

    private static ClassName errorListClass(String outputPackage) {
        return ClassName.get(outputPackage + ".schema", OutcomeClassGenerator.CLASS_NAME)
            .nestedClass(OutcomeClassGenerator.ERROR_LIST_CLASS);
    }
}
