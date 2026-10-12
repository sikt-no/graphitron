package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.rewrite.generators.schema.ErrorMappingsClassGenerator;
import no.sikt.graphitron.rewrite.generators.schema.ErrorRouterClassGenerator;
import no.sikt.graphitron.rewrite.generators.schema.OutcomeClassGenerator;
import no.sikt.graphitron.rewrite.model.ErrorChannel;

/**
 * Emits the body of a synchronous fetcher's {@code catch (Throwable e)} arm for a field whose
 * channel is {@link ErrorChannel.Mapped}: the typed-{@code Outcome}-wrapper transport, shared by
 * root {@code @service} fields and the DML and {@code @routine} payload carriers. This is the
 * {@code Mapped} half of the channel-emit partition; the {@link ErrorChannel.RouterDispatched}
 * arm emits through the {@code catchArm} / {@code asyncRouterCall} seams in
 * {@link TypeFetcherGenerator}, and the parameter types on both sides keep the partition
 * compiler-checked. The arm's spelling is
 * {@link no.sikt.graphitron.render.ErrorDispatchFragments#outcomeArm}, which the command-seam
 * renderers reach through their dispatch row; this host resolves the generated units off the
 * classifier's channel and delegates.
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
        return no.sikt.graphitron.render.ErrorDispatchFragments.outcomeArm(
            ErrorRouterClassGenerator.mappingInterface(outputPackage),
            ErrorMappingsClassGenerator.mappingsClass(outputPackage),
            channel.mappingsConstantName(), valueType, errorListClass(outputPackage),
            ErrorRouterClassGenerator.routerClass(outputPackage));
    }

    private static ClassName errorListClass(String outputPackage) {
        var outcome = new no.sikt.graphitron.plan.GeneratedUnits(outputPackage).outcome();
        return ClassName.get(outcome.packageName(), outcome.simpleName())
            .nestedClass(OutcomeClassGenerator.ERROR_LIST_CLASS);
    }
}
