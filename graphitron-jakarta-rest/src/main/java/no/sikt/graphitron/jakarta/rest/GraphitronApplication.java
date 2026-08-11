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
     * <p>This runs after the resource method that delegated to the library, so a
     * {@code @RequestScoped} holder the resource populated (claims read from an {@code Authorization}
     * header, a path parameter, anything else resolved per request) is visible here.
     *
     * <p>Throwing a {@link jakarta.ws.rs.WebApplicationException} from this method is the supported
     * way to answer a client fault: {@link jakarta.ws.rs.NotAuthorizedException} for {@code 401},
     * {@link jakarta.ws.rs.ForbiddenException} for {@code 403}, and so on. The library re-throws it
     * unredacted so the container maps the status the adapter chose. Every <em>other</em> exception
     * escaping this method is treated as an internal fault and redacted to a reference-only
     * {@code 500}, with the real cause logged under a correlation id, so no adapter internals reach
     * the client.
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

    /**
     * Whether the library's own {@link GraphqlResource} answers on {@code /graphql}. Defaults to
     * {@code true}. Override to {@code false} when the subgraph mounts
     * {@link GraphqlHttpHandler} on a path of its own and the ungated built-in route would be a hole
     * in that boundary: bean discovery registers the built-in resource automatically, so a consumer
     * cannot simply decline to use it.
     *
     * <p><strong>This is a 404 gate, not a de-registration.</strong> With the toggle off the
     * resource class is still discovered, still on the routing table, and still occupies the
     * {@code /graphql} namespace; every one of its routes answers {@code 404}. A consumer that needs
     * the route genuinely gone declares a {@link jakarta.ws.rs.core.Application} subclass with an
     * explicit {@code getClasses()}, which needs no support from this library. The trade-off is why
     * this toggle exists at all: in Quarkus such a declaration becomes definitive for the whole
     * application, which is a large hammer for excluding one resource.
     *
     * <p>Note also that mounting under {@code /graphql/{something}} shadows the built-in resource's
     * sub-paths whatever this toggle says, because Jakarta REST prefers the class with more literal
     * characters; see {@link GraphqlHttpHandler} for what that costs. The toggle governs the routes
     * that still reach the built-in resource, of which bare {@code /graphql} is the one that
     * matters.
     *
     * <p>The toggle rides this SPI seam for the same reason {@link #graphiqlEnabled()} does: the
     * library pulls no config framework, so a consumer that wants this wired to its own
     * configuration overrides the method.
     *
     * @return {@code true} to serve the built-in {@code /graphql} endpoint, {@code false} to answer
     *         {@code 404} on all of its routes
     */
    default boolean builtInEndpointEnabled() {
        return true;
    }
}
