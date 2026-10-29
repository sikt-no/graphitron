package no.sikt.graphitron.sakila.example.app;

import graphql.ExecutionResult;
import graphql.execution.instrumentation.InstrumentationContext;
import graphql.execution.instrumentation.InstrumentationState;
import graphql.execution.instrumentation.SimplePerformantInstrumentation;
import graphql.execution.instrumentation.parameters.InstrumentationExecuteOperationParameters;
import no.sikt.graphitron.generated.schema.TenantConnections;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * An application's own instrumentation (OpenTelemetry's stands in the real case), chained after
 * graphitron's through the generated runtime. It records how many operations it saw and, for each,
 * whether graphitron's connection instrumentation had already put its per-operation carrier in
 * the context when this one's {@code beginExecuteOperation} ran, which is what "after graphitron's"
 * means on the wire.
 */
public final class RecordingInstrumentation extends SimplePerformantInstrumentation {

    static final RecordingInstrumentation INSTANCE = new RecordingInstrumentation();

    final AtomicInteger operations = new AtomicInteger();
    final AtomicInteger operationsWithGraphitronsCarrierAlreadyPresent = new AtomicInteger();

    private RecordingInstrumentation() {
    }

    @Override
    public InstrumentationContext<ExecutionResult> beginExecuteOperation(
            InstrumentationExecuteOperationParameters parameters, InstrumentationState state) {
        operations.incrementAndGet();
        if (parameters.getExecutionContext().getGraphQLContext().hasKey(TenantConnections.class)) {
            operationsWithGraphitronsCarrierAlreadyPresent.incrementAndGet();
        }
        return super.beginExecuteOperation(parameters, state);
    }
}
