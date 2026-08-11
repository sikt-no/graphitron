package no.sikt.graphitron.sakila.example.app;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotAuthorizedException;
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
import no.sikt.graphitron.jakarta.rest.GraphiqlBundle;
import no.sikt.graphitron.jakarta.rest.GraphqlHttpHandler;
import no.sikt.graphitron.jakarta.rest.OperationPolicy;

/**
 * A consumer resource mounting the library's delegates on a path of its own, shaped like the worked
 * example in {@link GraphqlHttpHandler}'s javadoc: a path parameter binding the calling environment
 * structurally, authentication in the resource method before any delegation, and an operation policy
 * selected per request rather than configured once.
 *
 * <p>Mounted at {@code /env/{callingEnvironment}/graphql} rather than the realistic
 * {@code /graphql/{callingEnvironment}}: a {@code @Path} class in test sources is registered for
 * every {@code @QuarkusTest} deployment in this module, so a fixture overlapping {@code /graphql}
 * would shadow the built-in resource's sub-paths for every other test here, not just its own.
 * {@link OverlappingMountTest} pins the matching algorithm that produces that shadowing, on paths
 * that overlap each other and nothing else. Test sources for the same reason
 * {@link FaultInjectingGraphitronApplication} is here: the shipped reference app stays a pristine
 * copy-paste template.
 */
@Path("/env/{callingEnvironment}/graphql")
public class PolicyMountedGraphqlResource {

    /** Echoes what the SPI seam observed, so the ordering property is visible on the wire. */
    static final String OBSERVED_HEADER = "X-Observed-Environment";

    /** The path value that gets the unrestricted mount; every other value is queries-only. */
    static final String PRODUCTION = "production";

    private static final OperationPolicy QUERIES_ONLY =
        OperationPolicy.queriesOnly(Response.Status.BAD_REQUEST);

    @Inject GraphqlHttpHandler handler;
    @Inject GraphiqlBundle graphiql;
    @Inject CallingEnvironment environment;

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces({GraphqlHttpHandler.GRAPHQL_RESPONSE_JSON, MediaType.APPLICATION_JSON})
    public Response post(@PathParam("callingEnvironment") String callingEnvironment,
                         String body,
                         @Context HttpHeaders headers) {
        authenticate(headers);
        environment.requested(callingEnvironment);
        Response response = PRODUCTION.equals(callingEnvironment)
            ? handler.post(body, headers)                        // no policy: every operation
            : handler.post(body, headers, QUERIES_ONLY);         // 400 before execution
        return Response.fromResponse(response)
            .header(OBSERVED_HEADER, environment.observedBySeam())
            .build();
    }

    @GET
    @Produces({GraphqlHttpHandler.GRAPHQL_RESPONSE_JSON, MediaType.APPLICATION_JSON})
    public Response get(@PathParam("callingEnvironment") String callingEnvironment,
                        @QueryParam("query") String query,
                        @QueryParam("operationName") String operationName,
                        @QueryParam("variables") String variables,
                        @QueryParam("extensions") String extensions,
                        @Context HttpHeaders headers) {
        authenticate(headers);
        environment.requested(callingEnvironment);
        // Parameter order matches the four @QueryParam names directly above. GET carries the
        // specification's queries-only rule (405 + Allow: POST) with no policy argument to pass,
        // even on the production mount that passes no policy to post().
        return handler.get(query, operationName, variables, extensions, headers);
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
    @Path("/schema")
    @Produces(MediaType.TEXT_PLAIN)
    public String schema() {
        return handler.schema();
    }

    /**
     * Stands in for the consumer's real authentication. A {@code WebApplicationException} thrown
     * here, before any delegation, must reach the container unredacted.
     */
    private static void authenticate(HttpHeaders headers) {
        if (headers.getHeaderString(HttpHeaders.AUTHORIZATION) == null) {
            throw new NotAuthorizedException("Bearer");
        }
    }
}
