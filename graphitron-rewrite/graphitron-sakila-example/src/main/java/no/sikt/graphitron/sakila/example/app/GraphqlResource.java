package no.sikt.graphitron.sakila.example.app;

import graphql.ExecutionInput;
import graphql.ExecutionResult;
import io.agroal.api.AgroalDataSource;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import no.sikt.graphitron.generated.Graphitron;

import java.net.URI;
import java.util.Map;

/**
 * GraphQL-over-HTTP endpoint per the
 * <a href="https://graphql.github.io/graphql-over-http/">graphql-over-http</a> spec: POST
 * accepts {@code application/json} and returns {@code application/graphql-response+json};
 * GET accepts {@code ?query=&operationName=} for query-only requests. A browser hitting
 * {@code GET /graphql} (no query, {@code Accept: text/html}) is redirected to the
 * pre-built GraphiQL playground at {@code /graphiql/}.
 *
 * <p>Per-request wiring goes through {@link Graphitron#newExecutionInput} which threads
 * the {@link AppContext} under the typed context key every generated fetcher reads from
 * and attaches a fresh {@code DataLoaderRegistry} (graphql-java requires one even when
 * no DataLoader is used; split-fetchers rely on it for batching).
 */
@Path("/graphql")
public class GraphqlResource {

    public static final String GRAPHQL_RESPONSE = "application/graphql-response+json";

    @Inject GraphqlEngine engine;
    @Inject AgroalDataSource dataSource;

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces({GRAPHQL_RESPONSE, MediaType.APPLICATION_JSON})
    public Response graphqlPost(GraphqlRequest req) {
        return execute(req.query(), req.variables(), req.operationName());
    }

    @GET
    @Produces({GRAPHQL_RESPONSE, MediaType.APPLICATION_JSON})
    public Response graphqlGet(@QueryParam("query") String query,
                               @QueryParam("operationName") String operationName) {
        return execute(query, null, operationName);
    }

    @GET
    @Produces(MediaType.TEXT_HTML)
    public Response graphiqlRedirect() {
        return Response.seeOther(URI.create("/graphiql/")).build();
    }

    private Response execute(String query, Map<String, Object> variables, String operationName) {
        ExecutionInput.Builder input = Graphitron.newExecutionInput(new AppContext(dataSource, Map.of()))
            .query(query);
        if (variables != null) {
            input.variables(variables);
        }
        if (operationName != null) {
            input.operationName(operationName);
        }
        ExecutionResult result = engine.get().execute(input.build());
        return Response.ok(result.toSpecification(), GRAPHQL_RESPONSE).build();
    }

    public record GraphqlRequest(String query, Map<String, Object> variables, String operationName) {}
}
