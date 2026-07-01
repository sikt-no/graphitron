package no.sikt.graphitron.jakarta.rest;

import graphql.ExecutionInput;
import graphql.GraphQL;
import graphql.schema.GraphQLSchema;

/**
 * The dependency-inversion seam between {@code graphitron-jakarta-rest} and a Graphitron subgraph.
 *
 * <p>The library cannot name the generated {@code Graphitron} facade: that class lives in a
 * per-subgraph package, and {@code newExecutionInput} varies per schema in both arity and parameter
 * type (its context-arg values are resolved per request from auth). The subgraph supplies a small
 * adapter implementing this interface; the adapter is the only place that names the facade. The
 * library depends on this interface, never on a generated symbol.
 *
 * <p>This is a consumer-implemented SPI, so it is a plain (open) interface, not a sealed hierarchy:
 * the set of implementations is open by design, owned by each consuming subgraph rather than by the
 * generator. {@link AbstractGraphitronApplication} removes the boilerplate so a concrete adapter
 * writes only the auth-bearing {@link #newExecutionInput()} and, optionally, an
 * {@link #engineBuilder()} override.
 *
 * @see AbstractGraphitronApplication
 */
public interface GraphitronApplication {

    /**
     * The single executable schema. Source of truth for both the engine and the {@code /schema}
     * SDL endpoint, so the two can never drift. Expected to be cached by the implementation
     * ({@link AbstractGraphitronApplication} builds it once); the library reads it freely.
     *
     * @return the built {@link GraphQLSchema}
     */
    GraphQLSchema schema();

    /**
     * A per-request, auth-seeded {@link ExecutionInput.Builder}. The subgraph populates the
     * {@code DSLContext} and any declared {@code contextArguments} from the current request (via
     * {@code Graphitron.newExecutionInput(...)}); the library layers {@code query} /
     * {@code variables} / {@code operationName} / {@code extensions} from the HTTP body on top,
     * then executes.
     *
     * @return a builder pre-wired with this request's per-request context
     */
    ExecutionInput.Builder newExecutionInput();

    /**
     * Engine assembly via graphql-java's own builder seam. The library caches
     * {@code engineBuilder().build()} once at application scope. The default delegates to
     * {@link #schema()} so there is exactly one built schema feeding both the engine and the SDL
     * endpoint; override to chain {@code .instrumentation(...)} (e.g. OpenTelemetry
     * {@code GraphQLTelemetry}), a custom {@code ExecutionStrategy}, or any future engine knob.
     *
     * @return a {@link GraphQL.Builder} over {@link #schema()}, ready for {@code .build()}
     */
    default GraphQL.Builder engineBuilder() {
        return GraphQL.newGraphQL(schema());
    }

    /**
     * Whether the library serves the bundled GraphiQL page at {@code GET /graphql} with
     * {@code Accept: text/html}. Defaults to {@code true}. Override to gate GraphiQL behind the
     * subgraph's own configuration, e.g. {@code return ConfigProvider.getConfig().getValue(...)}.
     *
     * <p>The toggle rides this SPI seam (a default method, like {@link #engineBuilder()}) rather than
     * a config framework on purpose: the framework decision is vendor-neutral Jakarta with no
     * RESTEasy/Quarkus types, and the project forbids adding dependencies not already pinned in the
     * parent pom, so the library cannot reach for Quarkus {@code @ConfigProperty} or MicroProfile
     * Config itself. A consumer that wants the toggle wired to its own config overrides this method;
     * the library stays dependency-free.
     *
     * @return {@code true} to serve GraphiQL, {@code false} to return {@code 404} for the HTML page
     */
    default boolean graphiqlEnabled() {
        return true;
    }
}
