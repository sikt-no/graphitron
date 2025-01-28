package no.sikt.graphitron.example.server;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import graphql.ExecutionInput;
import graphql.language.OperationDefinition;
import graphql.parser.Parser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.jboss.resteasy.reactive.common.util.MediaTypeHelper;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class GraphqlHttpRequest {
    private static final List<MediaType> PROVIDED_MEDIA_TYPES = MediaTypeHelper.parseHeader("application/graphql-response+json,application/json");
    private static final ObjectMapper objectMapper = new ObjectMapper();
    public static ExecutionInput parse(HttpServletRequest request) throws IOException {
        if (HttpMethod.GET.equals(request.getMethod())) {
            var executionInfo = parseGet(request);
            if (isMutation(executionInfo)) {
                throw new NotAllowedException("You must use POST for mutations");
            }

            return executionInfo;
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

    private static ExecutionInput parseGet(HttpServletRequest request) {
        var query = request.getParameter("query");
        if (query == null) {
            throw new BadRequestException("The query parameter must be specified");
        }

        var operationName = request.getParameter("operationName");
        var variables = parseMap("The variables parameter must be an object", request.getParameter("variables"));
        var extensions = parseMap("The extensions parameter must be an object", request.getParameter("extensions"));

        var input = ExecutionInput.newExecutionInput(query);
        if (operationName != null) {
            input.operationName(operationName);
        }
        if (variables != null) {
            input.variables(variables);
        }
        if (extensions != null) {
            input.extensions(extensions);
        }

        return input.build();
    }

    private static Map<String, Object> parseMap(String onFail, String json) {
        if (json == null) {
            return null;
        }

        try {
            TypeReference<Map<String, Object>> mapTypeReference = new TypeReference<>() {};
            return objectMapper.readValue(json, mapTypeReference);
        } catch (JsonProcessingException e) {
            throw new BadRequestException(onFail);
        }
    }

    protected static boolean postedMediaTypeSupported(String contentType) {
        if (contentType == null) {
            return false;
        }

        var mediaType = MediaType.valueOf(contentType);
        return mediaType.isCompatible(MediaType.APPLICATION_JSON_TYPE);
    }

    private static ExecutionInput parsePost(HttpServletRequest request) throws IOException {
        // If the media type in a Content-Type or Accept header does not include encoding information [...]
        // then utf-8 MUST be assumed.
        if (request.getCharacterEncoding() == null) {
            request.setCharacterEncoding("utf-8");
        }

        if (!postedMediaTypeSupported(request.getContentType())) {
            throw new NotSupportedException("GraphQL over HTTP only supports application/json");
        }

        JsonNode rootNode;
        try {
            rootNode = objectMapper.readTree(request.getReader());
        } catch (JsonParseException e) {
            throw new BadRequestException("The provided query body does not parse");
        }

        var query = getText(rootNode, "query");
        if (query == null) {
            throw new BadRequestException("The query parameter must be specified");
        }

        var input = ExecutionInput.newExecutionInput(query);
        var operationName = getText(rootNode, "operationName");
        if (operationName != null) {
            input.operationName(operationName);
        }

        var variables = getObject(rootNode, "variables");
        if (variables != null) {
            input.variables(variables);
        }

        var extensions = getObject(rootNode, "extensions");
        if (extensions != null) {
            input.extensions(extensions);
        }

        return input.build();
    }

    private static String getText(JsonNode rootNode, String parameter) {
        if (!rootNode.has(parameter) || rootNode.get(parameter).isNull()) {
            return null;
        }

        var node = rootNode.get(parameter);
        if (!node.isTextual()) {
            throw new BadRequestException("The provided " + parameter + " is invalid");
        }

        return node.asText();
    }

    private static Map<String, Object> getObject(JsonNode rootNode, String parameter) {
        try {
            if (!rootNode.has(parameter)) {
                return null;
            }

            return objectMapper.convertValue(rootNode.get(parameter), new TypeReference<>() {});
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("The " + parameter + " parameter must be an object");
        }
    }

    protected static boolean isMutation(ExecutionInput input) {
        var operation = getOperation(input)
                .map(OperationDefinition::getOperation)
                .orElse(OperationDefinition.Operation.QUERY);
        return operation.equals(OperationDefinition.Operation.MUTATION);
    }

    private static Optional<OperationDefinition> getOperation(ExecutionInput input) {
        var document = Parser.parse(input.getQuery());
        var operationName = input.getOperationName();
        if (operationName != null) {
            return document.getOperationDefinition(operationName);
        }

        return document.getFirstDefinitionOfType(OperationDefinition.class);
    }
}
