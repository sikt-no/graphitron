package no.sikt.graphitron.jakarta.rest;

import graphql.ErrorType;
import graphql.ExecutionInput;
import graphql.ExecutionResult;
import graphql.GraphQLError;
import graphql.language.Document;
import graphql.language.OperationDefinition;
import graphql.parser.Parser;
import graphql.schema.idl.SchemaPrinter;
import jakarta.inject.Inject;
import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;
import jakarta.json.bind.JsonbConfig;
import jakarta.json.bind.JsonbException;
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

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

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
 * <p>Request parsing and response serialisation live in this resource, with <em>no</em> custom
 * JAX-RS {@code MessageBodyReader}/{@code Writer} providers: the resource reads the raw body, parses
 * it with the Jakarta JSON Binding ({@link Jsonb}) API into a {@link GraphqlRequest}, and both verbs
 * funnel through one {@link #execute} helper that builds the input via the {@link GraphitronApplication} seam,
 * executes, and serialises {@code result.toSpecification()}. Wire-format encoding stays at the HTTP
 * boundary, never in the model. Owning parsing lets the resource shape parse errors as spec
 * {@code 4xx} responses and own the request-error-vs-field-error status watershed, which a
 * {@code MessageBodyWriter} cannot set.
 */
@Path("/graphql")
public class GraphqlResource {

    /** The modern GraphQL-over-HTTP response media type. */
    public static final String GRAPHQL_RESPONSE_JSON = "application/graphql-response+json";

    private static final MediaType GRAPHQL_RESPONSE_TYPE =
        new MediaType("application", "graphql-response+json");

    private static final String GRAPHIQL_HTML = loadResource("graphiql.html");

    /**
     * The JSON binder, created once and shared (it is thread-safe and expensive to build). Resolved
     * from the JSON-B provider on the consumer's classpath via {@code ServiceLoader}; a Jakarta REST
     * runtime supplies one (Yasson on Jakarta EE servers; {@code quarkus-jsonb} in Quarkus).
     * {@code withNullValues(true)} keeps explicit nulls (a GraphQL field that resolves to {@code null}
     * must appear in {@code data}, not be dropped).
     */
    private static final Jsonb JSONB =
        JsonbBuilder.create(new JsonbConfig().withNullValues(true));

    @Inject GraphqlEngine engine;
    @Inject GraphitronApplication application;

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces({GRAPHQL_RESPONSE_JSON, MediaType.APPLICATION_JSON})
    public Response post(String body, @Context HttpHeaders headers) {
        boolean legacy = isLegacy(headers);
        if (isBlank(body)) {
            // Empty/absent body is not a well-formed GraphQL-over-HTTP request -> 422 (and avoids
            // handing a null/blank document to the JSON binder).
            return requestError(422, "The request body must be a JSON object with a 'query'.", legacy);
        }
        GraphqlRequest request;
        try {
            request = JSONB.fromJson(body, GraphqlRequest.class);
        } catch (JsonbException e) {
            // Not a well-formed GraphQL-over-HTTP request (body is not valid JSON) -> 422.
            return requestError(422, "Request body is not valid JSON.", legacy);
        }
        if (request == null || isBlank(request.query())) {
            return requestError(422, "The request must include a 'query' string.", legacy);
        }
        return execute(request, false, legacy);
    }

    @GET
    @Produces({GRAPHQL_RESPONSE_JSON, MediaType.APPLICATION_JSON})
    public Response get(@QueryParam("query") String query,
                        @QueryParam("operationName") String operationName,
                        @QueryParam("variables") String variables,
                        @QueryParam("extensions") String extensions,
                        @Context HttpHeaders headers) {
        boolean legacy = isLegacy(headers);
        if (isBlank(query)) {
            return requestError(422, "The request must include a 'query' parameter.", legacy);
        }
        Map<String, Object> variableMap;
        Map<String, Object> extensionMap;
        try {
            variableMap = parseMapParam(variables);
            extensionMap = parseMapParam(extensions);
        } catch (JsonbException e) {
            return requestError(422, "The 'variables'/'extensions' parameter is not valid JSON.", legacy);
        }
        return execute(new GraphqlRequest(query, operationName, variableMap, extensionMap), true, legacy);
    }

    @GET
    @Produces(MediaType.TEXT_HTML)
    public Response graphiql(@Context UriInfo uriInfo) {
        if (!application.graphiqlEnabled()) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        // Rewrite the {{ASSET_BASE}} placeholder to the absolute path of this page's assets
        // endpoint, so the self-hosted bundle resolves wherever the consumer mounts the resource
        // (/graphql, /api/graphql, ...). The page URL is the GraphQL endpoint itself; appending
        // "assets/" targets the streaming method below. Every downstream chunk/worker/font is
        // referenced relative to the entry files, so only the entry references need the absolute base.
        String base = uriInfo.getAbsolutePath().toString();
        if (!base.endsWith("/")) {
            base += "/";
        }
        String html = GRAPHIQL_HTML.replace("{{ASSET_BASE}}", base + "assets/");
        return Response.ok(html, MediaType.TEXT_HTML).build();
    }

    /**
     * Streams a committed GraphiQL bundle asset (built by {@code tools/graphiql-build}) from this
     * package's {@code graphiql/} classpath directory. Vendor-neutral: reads via
     * {@code getResourceAsStream} rather than relying on a container's static-asset serving.
     *
     * <p>The {@code name} is validated against a strict {@code [A-Za-z0-9._-]+} allowlist (and an
     * explicit {@code ..} reject) so it cannot escape the bundle directory, and its extension must
     * map to a known asset media type; anything else, or a missing resource, is a {@code 404}.
     * Gated behind {@link GraphitronApplication#graphiqlEnabled()}, same as the page itself.
     */
    @GET
    @Path("assets/{name}")
    public Response asset(@PathParam("name") String name) {
        if (!application.graphiqlEnabled()) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        if (name == null || !name.matches("[A-Za-z0-9._-]+") || name.contains("..")) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        String mediaType = assetMediaType(name);
        if (mediaType == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        try (InputStream in = GraphqlResource.class.getResourceAsStream("graphiql/" + name)) {
            if (in == null) {
                return Response.status(Response.Status.NOT_FOUND).build();
            }
            return Response.ok(in.readAllBytes(), mediaType).build();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Maps a bundle asset's extension to its media type. Returns {@code null} for anything the
     * GraphiQL build does not emit, so unknown extensions fall through to a {@code 404}.
     */
    private static String assetMediaType(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0) {
            return null;
        }
        return switch (name.substring(dot + 1)) {
            case "js" -> "text/javascript";
            case "css" -> "text/css";
            case "map" -> "application/json";
            case "ttf" -> "font/ttf";
            case "woff" -> "font/woff";
            case "woff2" -> "font/woff2";
            case "svg" -> "image/svg+xml";
            default -> null;
        };
    }

    @GET
    @Path("/schema")
    @Produces(MediaType.TEXT_PLAIN)
    public String schema() {
        return new SchemaPrinter().print(application.schema());
    }

    /**
     * The single execution path for both verbs: enforce the GET-mutation rule, build the input via
     * the seam, execute, and shape the response (status + media type + serialised body).
     */
    private Response execute(GraphqlRequest request, boolean isGet, boolean legacy) {
        if (isGet) {
            OperationDefinition.Operation operation;
            try {
                operation = operationType(request.query(), request.operationName());
            } catch (Exception parseFailure) {
                // The GraphQL document cannot be parsed -> 400.
                return requestError(400, "The GraphQL document could not be parsed.", legacy);
            }
            if (operation != null && operation != OperationDefinition.Operation.QUERY) {
                // The spec mandates 405 for non-query operations over GET, regardless of media type.
                String kind = operation.name().toLowerCase();
                return Response.status(Response.Status.METHOD_NOT_ALLOWED)
                    .header(HttpHeaders.ALLOW, "POST")
                    .type(responseType(legacy))
                    .entity(serialise(errorBody("GraphQL " + kind
                        + " operations must use POST, not GET.")))
                    .build();
            }
        }

        ExecutionInput.Builder builder = application.newExecutionInput()
            .query(request.query())
            .operationName(request.operationName());
        if (request.variables() != null) {
            builder.variables(request.variables());
        }
        if (request.extensions() != null) {
            builder.extensions(request.extensions());
        }

        ExecutionResult result = engine.get().execute(builder.build());
        int status = legacy ? 200 : statusFor(result);
        return Response.status(status)
            .type(responseType(legacy))
            .entity(serialise(result.toSpecification()))
            .build();
    }

    /**
     * The media-type-driven status code for a produced {@link ExecutionResult} in modern mode.
     * <ul>
     *   <li>No errors, or execution began (data present, field errors only) -> {@code 200}.</li>
     *   <li>A request error prevented execution: unparseable document -> {@code 400}; any other
     *       request error (validation, variable coercion) -> {@code 422}.</li>
     * </ul>
     */
    private static int statusFor(ExecutionResult result) {
        if (result.getErrors().isEmpty() || result.isDataPresent()) {
            return 200;
        }
        for (GraphQLError error : result.getErrors()) {
            if (error.getErrorType() == ErrorType.InvalidSyntax) {
                return 400;
            }
        }
        return 422;
    }

    /**
     * Parses the document and resolves which operation a GET request would run, so the caller can
     * reject mutations/subscriptions with {@code 405}. Returns {@code null} when no operation can be
     * resolved (empty document, or an {@code operationName} that matches none): the engine then
     * produces the proper request error rather than the resource guessing. Propagates graphql-java's
     * parse exception when the document is syntactically invalid; the caller maps that to {@code 400}.
     */
    private static OperationDefinition.Operation operationType(String query, String operationName) {
        Document document = Parser.parse(query);
        List<OperationDefinition> operations = document.getDefinitionsOfType(OperationDefinition.class);
        if (operations.isEmpty()) {
            return null;
        }
        OperationDefinition chosen;
        if (!isBlank(operationName)) {
            chosen = operations.stream()
                .filter(operation -> operationName.equals(operation.getName()))
                .findFirst()
                .orElse(null);
        } else {
            chosen = operations.get(0);
        }
        return chosen == null ? null : chosen.getOperation();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseMapParam(String json) {
        if (isBlank(json)) {
            return null;
        }
        return JSONB.fromJson(json, Map.class);
    }

    private Response requestError(int modernStatus, String message, boolean legacy) {
        return Response.status(legacy ? 200 : modernStatus)
            .type(responseType(legacy))
            .entity(serialise(errorBody(message)))
            .build();
    }

    private static String serialise(Object payload) {
        return JSONB.toJson(payload);
    }

    private static Object errorBody(String message) {
        return Map.of("errors", List.of(Map.of("message", message)));
    }

    private static String responseType(boolean legacy) {
        return legacy ? MediaType.APPLICATION_JSON : GRAPHQL_RESPONSE_JSON;
    }

    /**
     * Legacy clients accept {@code application/json} but not {@code application/graphql-response+json}.
     * A wildcard ({@code *}/{@code *}) or an explicit {@code application/graphql-response+json}, and the
     * no-{@code Accept} default, are all treated as modern.
     */
    private static boolean isLegacy(HttpHeaders headers) {
        List<MediaType> accepted = headers.getAcceptableMediaTypes();
        boolean modern = accepted.stream().anyMatch(m -> m.isCompatible(GRAPHQL_RESPONSE_TYPE));
        if (modern) {
            return false;
        }
        return accepted.stream().anyMatch(m -> m.isCompatible(MediaType.APPLICATION_JSON_TYPE));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String loadResource(String name) {
        try (InputStream in = GraphqlResource.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException("Missing classpath resource: " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
