package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.rewrite.generators.schema.ErrorMappingsClassGenerator;
import no.sikt.graphitron.rewrite.generators.schema.ErrorRouterClassGenerator;
import no.sikt.graphitron.rewrite.generators.schema.OutcomeClassGenerator;
import no.sikt.graphitron.rewrite.model.ErrorChannel;

import java.util.List;

/**
 * Emits the body of a synchronous fetcher's {@code catch (Throwable e)} arm for a field whose
 * channel is {@link ErrorChannel.Mapped}: the typed-{@code Outcome}-wrapper transport. This is
 * the {@code Mapped} half of the channel-emit partition; the
 * {@link ErrorChannel.RouterDispatched} arms emit through the {@code catchArm} /
 * {@code asyncRouterCall} seams in {@link TypeFetcherGenerator}, and the parameter types on both
 * sides keep the partition compiler-checked.
 *
 * <p>{@code valueType} is the {@code Outcome<X>} parameterisation of the emitted
 * {@code DataFetcherResult}.
 */
public final class ChannelCatchArmEmitter {

    private ChannelCatchArmEmitter() {}

    public static CodeBlock emit(
            ErrorChannel.Mapped channel,
            TypeName valueType,
            String outputPackage) {
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
        return ErrorRouterClassGenerator.routerClass(outputPackage);
    }

    private static ClassName mappingInterface(String outputPackage) {
        return ErrorRouterClassGenerator.mappingInterface(outputPackage);
    }

    private static ClassName errorMappingsClass(String outputPackage) {
        return ErrorMappingsClassGenerator.mappingsClass(outputPackage);
    }

    private static ClassName errorListClass(String outputPackage) {
        var outcome = new no.sikt.graphitron.plan.GeneratedUnits(outputPackage).singleton(
            no.sikt.graphitron.plan.GeneratedUnits.SUB_SCHEMA, OutcomeClassGenerator.CLASS_NAME);
        return ClassName.get(outcome.packageName(), outcome.simpleName())
            .nestedClass(OutcomeClassGenerator.ERROR_LIST_CLASS);
    }
}
