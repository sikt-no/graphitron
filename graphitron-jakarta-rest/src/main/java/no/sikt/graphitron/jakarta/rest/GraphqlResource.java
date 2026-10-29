package no.sikt.graphitron.jakarta.rest;

import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;

/**
 * GraphQL-over-HTTP endpoint serving a Graphitron schema at {@code /graphql}, per the
 * <a href="https://graphql.github.io/graphql-over-http/draft/">GraphQL-over-HTTP specification</a>.
 *
 * <p>POST accepts {@code application/json} request bodies. Both POST and GET produce
 * {@code application/graphql-response+json} (modern) or {@code application/json} (legacy) by
 * content negotiation on the {@code Accept} header. GET is supported for read-only queries; a GET
 * resolving to a mutation returns {@code 405}. A browser hitting {@code GET /graphql} with
 * {@code Accept: text/html} gets the bundled GraphiQL page; {@code GET /graphql/schema} returns the
 * SDL.
 *
 * <p>This class is the library's own mount and holds no behaviour: one
 * {@link #requireBuiltInEndpoint() built-in-endpoint gate} and a locator that returns the
 * {@link GraphqlEndpoint.Request} carrying every arm, built over
 * {@link GraphitronApplication#newExecutionInput()}. A consumer that must serve GraphQL on a
 * different path, in particular one carrying a template parameter, or that must build the input
 * from what its resource has at hand or apply an operation policy HTTP verb semantics do not
 * express, writes its own resource that returns a {@link GraphqlEndpoint.Request} from a locator
 * instead of reusing or subclassing this one; {@link GraphqlEndpoint} carries a worked example. Such
 * a consumer normally also overrides {@link GraphitronApplication#builtInEndpointEnabled()}, so this
 * ungated route stops answering.
 *
 * <p>There are <em>no</em> custom JAX-RS {@code MessageBodyReader}/{@code Writer} providers behind
 * any of it: the handler reads the raw body, parses it with the Jakarta JSON Binding API into a
 * {@link GraphqlRequest}, and both verbs funnel through one pipeline that builds the input,
 * executes, and serialises {@code result.toSpecification()}. Wire-format encoding stays at the HTTP
 * boundary, never in the model. Owning parsing lets the library shape parse errors as spec
 * {@code 4xx} responses and own the request-error-vs-field-error status watershed, which a
 * {@code MessageBodyWriter} cannot set.
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

    @Inject GraphqlEndpoint endpoint;
    @Inject GraphitronApplication application;

    /**
     * Every route of the built-in endpoint: execution, GraphiQL, its assets and the SDL, all served
     * by the returned sub-resource, or {@code 404} when the consumer has turned the endpoint off.
     */
    @Path("")
    public GraphqlEndpoint.Request graphql() {
        requireBuiltInEndpoint();
        return endpoint.request(application::newExecutionInput);
    }

    /**
     * Answers {@code 404} for every route on this resource when the consumer has turned the built-in
     * endpoint off. Throws rather than returning a status because the locator returns the
     * sub-resource. The {@link NotFoundException} goes straight to the container, never through the
     * pipeline's {@code WebApplicationException} passthrough, since the gate runs before delegation.
     */
    private void requireBuiltInEndpoint() {
        if (!application.builtInEndpointEnabled()) {
            throw new NotFoundException();
        }
    }
}
