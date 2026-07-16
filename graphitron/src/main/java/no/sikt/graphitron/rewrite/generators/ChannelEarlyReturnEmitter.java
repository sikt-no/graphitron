package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.rewrite.generators.schema.OutcomeClassGenerator;

/**
 * Emits the Jakarta-violation early return for a service fetcher's validator pre-step:
 * {@code return DataFetcherResult.<P>newResult().data(new Outcome.ErrorList<>(violations)).build();}.
 * Replaces the legacy {@code TypeFetcherGenerator.declareEarlyPayloadFromErrors}, which built a
 * developer payload class from the violation list.
 *
 * <p>Channel-agnostic: under the {@code Outcome} wrapper the early return wraps the violation list
 * in {@code ErrorList} regardless of which {@code @error} types the channel maps, so unlike the
 * retired payload-factory it consults no {@link no.sikt.graphitron.rewrite.model.ErrorChannel}
 * carrier. {@code valueType} is the {@code DataFetcherResult} payload type (the {@code Outcome<X>}
 * parameterisation); {@code violationsLocal} is the local holding the {@code ConstraintViolation}
 * list (today {@code violations}).
 */
public final class ChannelEarlyReturnEmitter {

    private ChannelEarlyReturnEmitter() {}

    public static CodeBlock emit(TypeName valueType, String violationsLocal, String outputPackage) {
        return CodeBlock.of("return $T.<$T>newResult().data(new $T<>($L)).build();\n",
            ClassName.get("graphql.execution", "DataFetcherResult"),
            valueType,
            errorListClass(outputPackage),
            violationsLocal);
    }

    private static ClassName errorListClass(String outputPackage) {
        return ClassName.get(outputPackage + ".schema", OutcomeClassGenerator.CLASS_NAME)
            .nestedClass(OutcomeClassGenerator.ERROR_LIST_CLASS);
    }
}
