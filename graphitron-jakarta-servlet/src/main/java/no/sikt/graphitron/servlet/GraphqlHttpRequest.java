package no.sikt.graphitron.servlet;

import graphql.language.OperationDefinition;
import graphql.parser.Parser;
import jakarta.json.bind.JsonbBuilder;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.NotAcceptableException;
import jakarta.ws.rs.NotAllowedException;
import jakarta.ws.rs.NotSupportedException;
import jakarta.ws.rs.core.MediaType;
import org.jboss.resteasy.reactive.common.util.MediaTypeHelper;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

class GraphqlHttpRequest {
    protected static final String GRAPHQL_RESPONSE = "application/graphql-response+json";
    protected static final MediaType GRAPHQL_RESPONSE_TYPE = MediaType.valueOf(GRAPHQL_RESPONSE);
    private static final List<MediaType> PROVIDED_MEDIA_TYPES = List.of(GRAPHQL_RESPONSE_TYPE, MediaType.APPLICATION_JSON_TYPE);

    public record Payload(String query, String operationName, Map<String, Object> variables, Map<String, Object> extensions) { }

    public static Payload parse(HttpServletRequest request) throws IOException {
        if (HttpMethod.GET.equals(request.getMethod())) {
            var payload = parseGet(request);
            if (isMutation(payload.query, payload.operationName)) {
                throw new NotAllowedException("You must use POST for mutations");
            }

            return payload;
        }

        if (HttpMethod.POST.equals(request.getMethod())) {
            return parsePost(request);
        }

        throw new NotAllowedException("We only support GET and POST");
    }

    public static String parseRequestedMediaType(HttpServletRequest request) {
        return parseRequestedMediaType(request.getHeader("Accept"));
    }

    protected static String parseRequestedMediaType(String accept) {
        // SHOULD assume application/json content-type when accept is missing
        // SHOULD accept */* and use application/json for the content-type
        if (accept == null || accept.equals("*/*")) {
            return "application/json";
        }

        var desired = MediaTypeHelper.parseHeader(accept);
        var match = MediaTypeHelper.getBestMatch(desired, PROVIDED_MEDIA_TYPES);

        if (match == null) {
            throw new NotAcceptableException("We only support application/graphql-response+json and application/json");
        }

        return match.toString();
    }

    private static Payload parseGet(HttpServletRequest request) {
        var query = request.getParameter("query");
        if (query == null) {
            throw new BadRequestException("The query parameter must be specified");
        }

        var operationName = request.getParameter("operationName");
        var variables = parseMap("The variables parameter must be an object", request.getParameter("variables"));
        var extensions = parseMap("The extensions parameter must be an object", request.getParameter("extensions"));

        return new Payload(query, operationName, variables, extensions);
    }

    private static Payload parsePost(HttpServletRequest request) throws IOException {
        // If the media type in a Content-Type or Accept header does not include encoding information [...]
        // then utf-8 MUST be assumed.
        if (request.getCharacterEncoding() == null) {
            request.setCharacterEncoding("utf-8");
        }

        if (!postedMediaTypeSupported(request.getContentType())) {
            throw new NotSupportedException("GraphQL over HTTP only supports application/json");
        }

        var jsonb = JsonbBuilder.create();
        var payload = jsonb.fromJson(request.getReader(), Payload.class);
        if (payload.query() == null) {
            throw new BadRequestException("The query parameter must be specified");
        }

        return payload;
    }

    private static Map<String, Object> parseMap(String onFail, String json) {
        if (json == null) {
            return null;
        }

        var jsonb = JsonbBuilder.create();
        return jsonb.fromJson(json, new HashMap<String, Object>().getClass().getGenericSuperclass());
    }

    protected static boolean postedMediaTypeSupported(String contentType) {
        if (contentType == null) {
            return false;
        }

        var mediaType = MediaType.valueOf(contentType);
        return mediaType.isCompatible(MediaType.APPLICATION_JSON_TYPE);
    }

    protected static boolean isMutation(String query, String operationName) {
        var operation = getOperation(query, operationName)
                .map(OperationDefinition::getOperation)
                .orElse(OperationDefinition.Operation.QUERY);
        return operation.equals(OperationDefinition.Operation.MUTATION);
    }

    private static Optional<OperationDefinition> getOperation(String query, String operationName) {
        var document = Parser.parse(query);
        if (operationName != null) {
            return document.getOperationDefinition(operationName);
        }

        return document.getFirstDefinitionOfType(OperationDefinition.class);
    }
}
