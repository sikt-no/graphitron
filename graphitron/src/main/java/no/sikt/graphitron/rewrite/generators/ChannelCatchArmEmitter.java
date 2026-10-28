package no.sikt.graphitron.rewrite.generators;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.TypeName;
import no.sikt.graphitron.rewrite.generators.schema.ErrorMappingsClassGenerator;
import no.sikt.graphitron.rewrite.generators.schema.ErrorRouterClassGenerator;
import no.sikt.graphitron.rewrite.generators.schema.OutcomeClassGenerator;

/**
 * Emits the body of a synchronous fetcher's {@code catch (Throwable e)} arm for a producer that
 * catches into its outcome: the typed-{@code Outcome}-wrapper transport, shared by root
 * {@code @service} fields and the DML and {@code @routine} payload carriers; a batched child
 * {@code @service}'s asynchronous catch emits the same arm in {@link TypeFetcherGenerator}. The
 * arm's spelling is {@link no.sikt.graphitron.render.ErrorDispatchFragments#outcomeArm}; this host
 * resolves the generated units and delegates.
 *
 * <p>{@code mappingsConstant} is the {@code ErrorMappings} constant of the producer's outcome type,
 * {@link ErrorMappingsClassGenerator#channelConstantName}. {@code valueType} is the
 * {@code Outcome<X>} parameterisation of the emitted {@code DataFetcherResult}.
 */
public final class ChannelCatchArmEmitter {

    private ChannelCatchArmEmitter() {}

    public static CodeBlock emit(
            String mappingsConstant,
            TypeName valueType,
            String outputPackage) {
        return no.sikt.graphitron.render.ErrorDispatchFragments.outcomeArm(
            ErrorMappingsClassGenerator.mappingsClass(outputPackage),
            mappingsConstant, valueType, errorListClass(outputPackage),
            ErrorRouterClassGenerator.routerClass(outputPackage));
    }

    private static ClassName errorListClass(String outputPackage) {
        var outcome = new no.sikt.graphitron.plan.GeneratedUnits(outputPackage).outcome();
        return ClassName.get(outcome.packageName(), outcome.simpleName())
            .nestedClass(OutcomeClassGenerator.ERROR_LIST_CLASS);
    }
}
