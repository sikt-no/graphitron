package no.sikt.graphitron.servlet;

import graphql.ErrorClassification;
import graphql.ErrorType;
import graphql.ExecutionInput;
import graphql.ExecutionResult;
import graphql.GraphQL;
import graphql.GraphQLError;
import graphql.GraphQLException;
import jakarta.json.bind.JsonbBuilder;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.core.MediaType;
import no.sikt.graphitron.servlet.GraphqlHttpRequest.Payload;
import org.dataloader.DataLoaderRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

public abstract class GraphitronServlet extends HttpServlet {
    Logger logger = LoggerFactory.getLogger(GraphitronServlet.class);

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        handleGraphqlRequest(request, response);
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
        handleGraphqlRequest(request, response);
    }

    private ErrorClassification getClassificationFor(int status) {
        return switch (status) {
            case 401 -> ErrorClassification.errorClassification("Authentication");
            case 403 -> ErrorClassification.errorClassification("Authorization");
            case 400 -> ErrorType.InvalidSyntax;
            default -> ErrorClassification.errorClassification("Unknown");
        };
    }

    private ExecutionResult mkResult(Exception t, int status) {
        return ExecutionResult.newExecutionResult()
                .addError(
                        t instanceof GraphQLError ? (GraphQLError) t
                                : GraphQLError.newError().errorType(getClassificationFor(status))
                                        .message(Optional.ofNullable(t.getMessage()).orElse("unknown")).build())
                .build();
    }

    private void handleGraphqlRequest(HttpServletRequest request, HttpServletResponse response) throws IOException {
        var requestedMediaType = GraphqlHttpRequest.parseRequestedMediaType(request);
        try {
            var graphQL = getSchema(request);
            var payload = GraphqlHttpRequest.parse(request);
            var input = toExecutionInputBuilder(payload);
            var transformed = buildExecutionInput(input);
            var result = graphQL.execute(transformed);

            writeResponse(response, result, requestedMediaType);
        } catch (ClientErrorException e) {
            logger.error("GraphQL request failed because of a client error", e);
            writeResponse(response, mkResult(e, e.getResponse().getStatus()), requestedMediaType);
        } catch (Exception e) {
            logger.error("GraphQL request failed because of a server error", e);
            writeResponse(response, mkResult(e, 500), requestedMediaType);
        }
    }

    private static ExecutionInput.Builder toExecutionInputBuilder(Payload payload) {
        var input = ExecutionInput.newExecutionInput(payload.query());
        if (payload.operationName() != null) {
            input.operationName(payload.operationName());
        }

        if (payload.variables() != null) {
            input.variables(payload.variables());
        }

        if (payload.extensions() != null) {
            input.extensions(payload.extensions());
        }

        input.dataLoaderRegistry(new DataLoaderRegistry());

        return input;
    }

    protected abstract GraphQL getSchema(HttpServletRequest request);

    /**
     * Override this method to add custom context to the execution input.
     */
    protected ExecutionInput buildExecutionInput(ExecutionInput.Builder builder) {
        return builder.build();
    }

    private void writeResponse(HttpServletResponse response, ExecutionResult result, String requestedMediaType)
            throws IOException {
        if (result.isDataPresent() || requestedMediaType.equals(MediaType.APPLICATION_JSON)) {
            response.setStatus(HttpServletResponse.SC_OK);
        } else {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        }

        response.setContentType(requestedMediaType);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());

        var jsonb = JsonbBuilder.create();
        jsonb.toJson(result.toSpecification(), response.getWriter());
    }

    protected ExecutionResult execute(GraphQL graphQL, ExecutionInput input) {
        return graphQL.execute(input);
    }
}
