package no.sikt.graphitron.rewrite.generators.util;

import graphql.ExecutionInput;
import graphql.ExecutionResult;
import graphql.GraphQL;
import graphql.execution.instrumentation.Instrumentation;
import graphql.execution.instrumentation.InstrumentationContext;
import graphql.execution.instrumentation.InstrumentationState;
import graphql.execution.instrumentation.parameters.InstrumentationFieldParameters;
import graphql.schema.DataFetcher;
import graphql.schema.GraphQLSchema;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.SchemaParser;

import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Drives an emitted owned-connection runtime through a real graphql-java engine to the ways a
 * request can end: normally, cancelled through {@code ExecutionInput.cancel()}, or abandoned by an
 * exception thrown during the execution strategy's synchronous dispatch of a root field. The
 * engine is the runtime's own {@code newGraphQL(schema, instrumentations...)}, so Graphitron's
 * instrumentation stays first in the chain and {@link AbortAt} runs after it.
 *
 * <p>The schema has the same root fields on {@code Query} and {@code Mutation}; each field's fetcher
 * resolves a context through the per-operation carrier, which pins and mounts on first demand.
 */
final class SynchronousAbortDriver {

    /** What {@link AbortAt} does at {@code beginFieldExecution} of its target root field. */
    enum Abort { NONE, CANCEL, THROW }

    /** The exception {@link Abort#THROW} raises, so a test can recognise it as the cause. */
    static final class InjectedFailure extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        InjectedFailure(String field) {
            super("injected at beginFieldExecution of " + field);
        }
    }

    /**
     * A consumer's chained instrumentation that cancels the request, or throws, at
     * {@code beginFieldExecution} of one root field: the points {@code AsyncExecutionStrategy}
     * checks or calls outside its per-field {@code try}.
     */
    static final class AbortAt implements Instrumentation {
        private final String field;
        private final Abort abort;

        AbortAt(String field, Abort abort) {
            this.field = field;
            this.abort = abort;
        }

        @Override
        public InstrumentationContext<Object> beginFieldExecution(InstrumentationFieldParameters parameters,
                                                                  InstrumentationState state) {
            var info = parameters.getExecutionStepInfo();
            if (info.getPath().getLevel() == 1 && info.getPath().getSegmentName().equals(field)) {
                switch (abort) {
                    case CANCEL -> parameters.getExecutionContext().getExecutionInput().cancel();
                    case THROW -> throw new InjectedFailure(field);
                    case NONE -> { }
                }
            }
            return null;
        }
    }

    private SynchronousAbortDriver() {}

    /**
     * Builds the engine over {@code runtime} with {@code abortAt} chained after Graphitron's
     * instrumentation. {@code fields} name the root fields, each wired to the carrier call
     * {@code acquire} makes for it.
     */
    static GraphQL engine(Object runtime, List<String> fields, Class<?> carrierClass,
                          Function<String, CarrierCall> acquire, Instrumentation abortAt) throws Throwable {
        String sdl = "type Query { " + String.join(" ", fields.stream().map(f -> f + ": String").toList()) + " }\n"
            + "type Mutation { " + String.join(" ", fields.stream().map(f -> f + ": String").toList()) + " }\n";
        var wiring = RuntimeWiring.newRuntimeWiring();
        for (String type : List.of("Query", "Mutation")) {
            wiring.type(type, t -> {
                for (String f : fields) {
                    CarrierCall call = acquire.apply(f);
                    DataFetcher<Object> fetcher = env -> {
                        Object carrier = env.getGraphQlContext().get(carrierClass);
                        try {
                            call.on(carrier);
                        } catch (InvocationTargetException e) {
                            throw e.getCause() instanceof Exception x ? x : new RuntimeException(e.getCause());
                        }
                        return f;
                    };
                    t.dataFetcher(f, fetcher);
                }
                return t;
            });
        }
        GraphQLSchema schema = new SchemaGenerator().makeExecutableSchema(new SchemaParser().parse(sdl), wiring.build());
        var newGraphQL = runtime.getClass().getMethod("newGraphQL", GraphQLSchema.class, Instrumentation[].class);
        try {
            return ((GraphQL.Builder) newGraphQL.invoke(runtime, schema, new Instrumentation[] {abortAt})).build();
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    /** Runs {@code operation} ({@code "query"} or {@code "mutation"}) selecting every field in order. */
    static CompletableFuture<ExecutionResult> execute(GraphQL engine, String operation, List<String> fields,
                                                     Map<Object, Object> context) {
        var input = ExecutionInput.newExecutionInput(operation + " { " + String.join(" ", fields) + " }")
            .graphQLContext(context)
            .build();
        return engine.executeAsync(input);
    }

    /** One fetcher's acquisition through the carrier, reflective since the carrier is emitted. */
    @FunctionalInterface
    interface CarrierCall {
        void on(Object carrier) throws Exception;
    }
}
