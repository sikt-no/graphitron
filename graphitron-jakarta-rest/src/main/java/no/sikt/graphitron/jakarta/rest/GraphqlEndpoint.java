package no.sikt.graphitron.jakarta.rest;

import graphql.ExecutionInput;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

import java.util.Objects;
import java.util.function.Supplier;

// The consumer example in the javadoc below writes its annotations as &#64; and sits in a bare <pre>
// rather than a {@code} block: javadoc reads an @ in a line's first column as a block tag even
// inside {@code}, and silently truncates the class description there. Keep the escapes.
/**
 * The whole GraphQL-over-HTTP surface (execution on POST and GET, the GraphiQL page, its assets and
 * the SDL) as a sub-resource a consumer's own resource returns from a locator method, at any path,
 * under any authentication. The resource hands over what is its own for this request: how to build
 * the execution input, and optionally which operations the endpoint allows.
 *
 * <pre>
 * &#64;Path("/graphql/{callingEnvironment}")
 * public class EnvironmentGraphqlResource {
 *
 *     private static final OperationPolicy QUERIES_ONLY =
 *         OperationPolicy.queriesOnly(Response.Status.BAD_REQUEST);
 *
 *     &#64;Inject GraphqlEndpoint graphql;
 *     &#64;Inject Authenticator authenticator;
 *
 *     &#64;Path("")
 *     public GraphqlEndpoint.Request graphql(&#64;PathParam("callingEnvironment") String environment,
 *                                            &#64;Context HttpHeaders headers) {
 *         Supplier&lt;ExecutionInput.Builder&gt; input = () -&gt; Graphitron.newOwnedExecutionInput(
 *             sessionFor(authenticator.login(headers, environment), environment));
 *         return "production".equals(environment)
 *             ? graphql.request(input)
 *             : graphql.request(input, QUERIES_ONLY);
 *     }
 * }
 * </pre>
 *
 * <p>Properties that sketch depends on:
 *
 * <ul>
 *   <li><b>The input supplier runs only when an operation executes</b>, on the request thread,
 *       inside the guard that passes a {@link jakarta.ws.rs.WebApplicationException} through
 *       unredacted and redacts every other fault. The GraphiQL page, its assets and the SDL never
 *       call it, so they need no authentication; a {@link jakarta.ws.rs.NotAuthorizedException}
 *       from the supplier reaches the container with the status the resource chose. A request
 *       answered before execution (an empty body, a document the policy refuses) never calls it
 *       either.</li>
 *   <li><b>Capture values, not the request.</b> The supplier reads what it needs from the request
 *       when it runs, and what it hands to graphitron are values: a session identity's callbacks
 *       may run on another thread, where a request-scoped proxy is not valid.</li>
 *   <li><b>Nothing here reads {@link GraphitronApplication#builtInEndpointEnabled()}</b>, which
 *       governs the library's own {@code /graphql} resource only; {@link
 *       GraphitronApplication#graphiqlEnabled()} still gates the GraphiQL page and its assets.</li>
 *   <li><b>The sub-paths are relative to the locator:</b> {@code assets/{name}} and {@code schema}
 *       under the path the resource is mounted at, and the page resolves its asset base from the
 *       request URI.</li>
 * </ul>
 *
 * <p>The two verbs carry their rules as {@link GraphqlHttpHandler} documents: POST takes the policy
 * the resource selects, GET carries the specification's queries-only rule unconditionally.
 */
@ApplicationScoped
public class GraphqlEndpoint {

    @Inject GraphqlHttpHandler handler;
    @Inject GraphiqlBundle graphiql;

    /**
     * An endpoint with no operation-type restriction: whatever a document resolves to runs, subject
     * to the schema.
     *
     * @param input builds this request's execution input, called only when an operation executes;
     *              must not be {@code null}
     * @return the sub-resource to return from a locator method
     */
    public Request request(Supplier<ExecutionInput.Builder> input) {
        return new Request(handler, graphiql, Objects.requireNonNull(input, "input"), null);
    }

    /**
     * An endpoint whose POST requests are checked against {@code policy} before execution. GET
     * keeps the specification's queries-only rule whatever the policy says.
     *
     * @param input  builds this request's execution input, called only when an operation executes;
     *               must not be {@code null}
     * @param policy the operation-type policy; must not be {@code null}
     * @return the sub-resource to return from a locator method
     */
    public Request request(Supplier<ExecutionInput.Builder> input, OperationPolicy policy) {
        return new Request(handler, graphiql, Objects.requireNonNull(input, "input"),
            Objects.requireNonNull(policy, "policy"));
    }

    /**
     * One request's view of the endpoint, built by {@link #request(Supplier)} or
     * {@link #request(Supplier, OperationPolicy)} and introspected by the container as a
     * sub-resource. Holds no behaviour: each method is one call into {@link GraphqlHttpHandler} or
     * {@link GraphiqlBundle}.
     */
    public static class Request {

        private final GraphqlHttpHandler handler;
        private final GraphiqlBundle graphiql;
        private final Supplier<ExecutionInput.Builder> input;
        private final OperationPolicy policy;

        Request(GraphqlHttpHandler handler, GraphiqlBundle graphiql,
                Supplier<ExecutionInput.Builder> input, OperationPolicy policy) {
            this.handler = handler;
            this.graphiql = graphiql;
            this.input = input;
            this.policy = policy;
        }

        @POST
        @Consumes(MediaType.APPLICATION_JSON)
        @Produces({GraphqlHttpHandler.GRAPHQL_RESPONSE_JSON, MediaType.APPLICATION_JSON})
        public Response post(String body, @Context HttpHeaders headers) {
            return policy == null
                ? handler.post(body, headers, input)
                : handler.post(body, headers, policy, input);
        }

        @GET
        @Produces({GraphqlHttpHandler.GRAPHQL_RESPONSE_JSON, MediaType.APPLICATION_JSON})
        public Response get(@QueryParam("query") String query,
                            @QueryParam("operationName") String operationName,
                            @QueryParam("variables") String variables,
                            @QueryParam("extensions") String extensions,
                            @Context HttpHeaders headers) {
            return handler.get(query, operationName, variables, extensions, headers, input);
        }

        @GET
        @Produces(MediaType.TEXT_HTML)
        public Response graphiql(@Context UriInfo uriInfo) {
            return graphiql.page(uriInfo);
        }

        @GET
        @Path("assets/{name}")
        public Response asset(@PathParam("name") String name) {
            return graphiql.asset(name);
        }

        @GET
        @Path("schema")
        @Produces(MediaType.TEXT_PLAIN)
        public String schema() {
            return handler.schema();
        }
    }
}
