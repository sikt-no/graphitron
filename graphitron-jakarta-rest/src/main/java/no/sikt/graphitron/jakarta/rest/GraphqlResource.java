package no.sikt.graphitron.jakarta.rest;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
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

/**
 * GraphQL-over-HTTP endpoint serving a Graphitron schema, per the
 * <a href="https://graphql.github.io/graphql-over-http/draft/">GraphQL-over-HTTP specification</a>.
 *
 * <p>POST accepts {@code application/json} request bodies. Both POST and GET produce
 * {@code application/graphql-response+json} (modern) or {@code application/json} (legacy) by
 * content negotiation on the {@code Accept} header. GET is supported for read-only queries; a GET
 * resolving to a mutation returns {@code 405}. A browser hitting {@code GET /graphql} with
 * {@code Accept: text/html} gets the bundled GraphiQL page; {@code GET /graphql/schema} returns the
 * SDL.
 *
 * <p>This class is the library's own mount and holds no behaviour: each method is the
 * {@link #requireBuiltInEndpoint() built-in-endpoint gate} plus one call into
 * {@link GraphqlHttpHandler} (the execution pipeline) or {@link GraphiqlBundle} (the playground).
 * A consumer that must serve GraphQL on a different path, in particular one carrying a template
 * parameter, or that must apply an operation policy HTTP verb semantics do not express, injects
 * those two delegates into its own resource rather than reusing or subclassing this one;
 * {@link GraphqlHttpHandler} carries a worked example. Such a consumer normally also overrides
 * {@link GraphitronApplication#builtInEndpointEnabled()}, so this ungated route stops answering.
 *
 * <p>There are <em>no</em> custom JAX-RS {@code MessageBodyReader}/{@code Writer} providers behind
 * any of it: the handler reads the raw body, parses it with the Jakarta JSON Binding API into a
 * {@link GraphqlRequest}, and both verbs funnel through one pipeline that builds the input via the
 * {@link GraphitronApplication} seam, executes, and serialises {@code result.toSpecification()}.
 * Wire-format encoding stays at the HTTP boundary, never in the model. Owning parsing lets the
 * library shape parse errors as spec {@code 4xx} responses and own the request-error-vs-field-error
 * status watershed, which a {@code MessageBodyWriter} cannot set.
 */
@Path("/graphql")
public class GraphqlResource {

    /**
     * The modern GraphQL-over-HTTP response media type. Re-exported from
     * {@link GraphqlHttpHandler#GRAPHQL_RESPONSE_JSON} so consumer code that already writes
     * {@code @Produces({GraphqlResource.GRAPHQL_RESPONSE_JSON, ...})} keeps compiling. The
     * initialiser is a constant expression, which is what keeps this field usable in an annotation;
     * do not "simplify" it into a method call.
     */
    public static final String GRAPHQL_RESPONSE_JSON = GraphqlHttpHandler.GRAPHQL_RESPONSE_JSON;

    @Inject GraphqlHttpHandler handler;
    @Inject GraphiqlBundle graphiql;
    @Inject GraphitronApplication application;

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces({GRAPHQL_RESPONSE_JSON, MediaType.APPLICATION_JSON})
    public Response post(String body, @Context HttpHeaders headers) {
        requireBuiltInEndpoint();
        return handler.post(body, headers);
    }

    @GET
    @Produces({GRAPHQL_RESPONSE_JSON, MediaType.APPLICATION_JSON})
    public Response get(@QueryParam("query") String query,
                        @QueryParam("operationName") String operationName,
                        @QueryParam("variables") String variables,
                        @QueryParam("extensions") String extensions,
                        @Context HttpHeaders headers) {
        requireBuiltInEndpoint();
        return handler.get(query, operationName, variables, extensions, headers);
    }

    @GET
    @Produces(MediaType.TEXT_HTML)
    public Response graphiql(@Context UriInfo uriInfo) {
        requireBuiltInEndpoint();
        return graphiql.page(uriInfo);
    }

    @GET
    @Path("assets/{name}")
    public Response asset(@PathParam("name") String name) {
        requireBuiltInEndpoint();
        return graphiql.asset(name);
    }

    @GET
    @Path("/schema")
    @Produces(MediaType.TEXT_PLAIN)
    public String schema() {
        requireBuiltInEndpoint();
        return handler.schema();
    }

    /**
     * Answers {@code 404} for every route on this resource when the consumer has turned the built-in
     * endpoint off. Throws rather than returning a status because {@link #schema()} returns
     * {@code String}: a status-returning gate is expressible in four of these five methods and not
     * the fifth, and widening a published return type for the gate's sake is the wrong trade. The
     * {@link NotFoundException} goes straight to the container, never through the pipeline's
     * {@code WebApplicationException} passthrough, since the gate runs before delegation.
     */
    private void requireBuiltInEndpoint() {
        if (!application.builtInEndpointEnabled()) {
            throw new NotFoundException();
        }
    }
}
