package no.sikt.graphitron.sakila.example.app;

import graphql.ExecutionInput;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotAuthorizedException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import no.sikt.graphitron.generated.Graphitron;
import no.sikt.graphitron.jakarta.rest.GraphqlEndpoint;
import no.sikt.graphitron.jakarta.rest.OperationPolicy;

import java.util.function.Supplier;

/**
 * A consumer resource serving the whole GraphQL-over-HTTP surface from its own path through
 * {@link GraphqlEndpoint}, with nothing carried between beans: the path parameter and the
 * {@code Authorization} header are read where the input is built and travel as values. This is the
 * shape the library's javadoc recommends; {@link PolicyMountedGraphqlResource} stays on the
 * delegate-and-holder flow, which still works.
 *
 * <p>Mounted at {@code /endpoint/{caller}/graphql}, not under {@code /graphql}, for the reason
 * {@link PolicyMountedGraphqlResource} gives. The supplier authenticates (a missing
 * {@code Authorization} header is a 401) and builds the input with a {@code SessionState} over the
 * caller as claims and the caller as the schema's {@code userId}, so a response shows which values
 * reached which request.
 */
@Path("/endpoint/{caller}/graphql")
public class EndpointMountedGraphqlResource {

    /** The path value that gets the unrestricted endpoint; every other value is queries-only. */
    static final String PRODUCTION = "production";

    /** A request carrying this header is built with claims the database rejects. */
    static final String REJECT_HEADER = "X-Reject-Identity";

    private static final OperationPolicy QUERIES_ONLY =
        OperationPolicy.queriesOnly(Response.Status.BAD_REQUEST);

    @Inject GraphqlEndpoint graphql;

    @Path("")
    public GraphqlEndpoint.Request graphql(@PathParam("caller") String caller, @Context HttpHeaders headers) {
        Supplier<ExecutionInput.Builder> input = () -> {
            if (headers.getHeaderString(HttpHeaders.AUTHORIZATION) == null) {
                throw new NotAuthorizedException("Bearer");
            }
            String claims = headers.getHeaderString(REJECT_HEADER) != null
                ? "{\"sub\":\"reject-me\"}"
                : "{\"sub\":\"" + caller + "\"}";
            return Graphitron.newOwnedExecutionInput(SakilaSessions.of(claims), claims, caller);
        };
        return PRODUCTION.equals(caller) ? graphql.request(input) : graphql.request(input, QUERIES_ONLY);
    }
}
