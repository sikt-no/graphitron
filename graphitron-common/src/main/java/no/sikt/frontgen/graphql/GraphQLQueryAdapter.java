package no.sikt.frontgen.graphql;

import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.HashMap;
import java.util.Map;

public class GraphQLQueryAdapter {
    private static final Logger LOG = LoggerFactory.getLogger(GraphQLQueryAdapter.class);
    private final HttpClient httpClient;
    private final Jsonb jsonb;

    public GraphQLQueryAdapter() {
        this.httpClient = HttpClient.newHttpClient();
        this.jsonb = JsonbBuilder.create();
    }

    public <T> T executeQuery(String query, Class<T> responseType) {
        return executeQuery(query, null, null, responseType);
    }

    public <T> T executeQuery(String query, Map<String, Object> variables, Class<T> responseType) {
        return executeQuery(query, variables, null, responseType);
    }

    public <T> T executeQuery(String query, Map<String, Object> variables, String operationName, Class<T> responseType) {
        Map<String, Object> request = new HashMap<>();
        request.put("query", query);

        if (variables != null) {
            request.put("variables", variables);
        }

        if (operationName != null) {
            request.put("operationName", operationName);
        }

        String requestBody = jsonb.toJson(request);

        String url = "http://localhost:8088/graphql";

        try {
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(httpRequest, BodyHandlers.ofString());
            return jsonb.fromJson(response.body(), responseType);
        } catch (IOException | InterruptedException e) {
            LOG.error("Error executing GraphQL query", e);
            throw new RuntimeException("Failed to execute GraphQL query", e);
        }
    }

    public <T> T executeQueryWithVariables(String query, Map<String, Object> variables, Class<T> responseType) {
        return executeQuery(query, variables, responseType);
    }
}