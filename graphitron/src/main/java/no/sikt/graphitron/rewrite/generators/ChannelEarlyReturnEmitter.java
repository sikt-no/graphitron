package no.sikt.graphitron.rewrite.generators;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.TypeName;
import no.sikt.graphitron.rewrite.generators.schema.OutcomeClassGenerator;

/**
 * Emits the early return of a refusal by a {@code VALIDATION} pre-step, a service fetcher's
 * Jakarta validation or a DML fetcher's length check:
 * {@code OwnedConnections.markFieldRefused(env, violations);} then
 * {@code return DataFetcherResult.<P>newResult().data(new Outcome.ErrorList<>(violations)).build();}.
 * The refusal fails the running mutation field, so its transaction rolls back and its
 * {@code @outbox(on: ERROR)} messages are written; the payload still carries the errors.
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
        var ownedConnections = new no.sikt.graphitron.plan.GeneratedUnits(outputPackage).ownedConnections();
        return CodeBlock.of("$T.markFieldRefused(env, $L);\nreturn $T.<$T>newResult().data(new $T<>($L)).build();\n",
            ClassName.get(ownedConnections.packageName(), ownedConnections.simpleName()), violationsLocal,
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
