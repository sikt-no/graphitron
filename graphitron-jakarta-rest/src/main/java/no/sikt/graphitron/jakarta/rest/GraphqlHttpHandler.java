package no.sikt.graphitron.jakarta.rest;

import graphql.ErrorType;
import graphql.ExecutionInput;
import graphql.ExecutionResult;
import graphql.GraphQLError;
import graphql.language.Document;
import graphql.language.OperationDefinition;
import graphql.parser.Parser;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;
import jakarta.json.bind.JsonbConfig;
import jakarta.json.bind.JsonbException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

// The consumer example in the javadoc below writes its annotations as &#64; and sits in a bare <pre>
// rather than a {@code} block: javadoc reads an @ in a line's first column as a block tag even
// inside {@code}, and silently truncates the class description there. Keep the escapes.
/**
 * The GraphQL-over-HTTP execution pipeline as a mountable delegate: decode the request, decide
 * whether its operation may run, execute, encode the response. Every Jakarta REST input arrives as a
 * parameter rather than through {@code @Context} injection, so a consumer's own resource can mount
 * this at any path, under any authentication, with any operation policy.
 *
 * <p>Most consumers do not call this directly: {@link GraphqlEndpoint} wraps it, with GraphiQL, its
 * assets and the SDL, as a sub-resource a resource returns from a locator, and
 * {@link GraphqlResource} is the library's own mount of that at {@code /graphql}. A consumer that
 * wants the arms spelled out in its own resource, or only some of them, calls the delegate itself:
 *
 * <pre>
 * &#64;Path("/graphql/{callingEnvironment}")
 * public class EnvironmentGraphqlResource {
 *
 *     private static final OperationPolicy QUERIES_ONLY =
 *         OperationPolicy.queriesOnly(Response.Status.BAD_REQUEST);
 *
 *     &#64;Inject GraphqlHttpHandler handler;
 *     &#64;Inject Authenticator authenticator;
 *
 *     &#64;POST
 *     &#64;Consumes(MediaType.APPLICATION_JSON)
 *     &#64;Produces({GraphqlHttpHandler.GRAPHQL_RESPONSE_JSON, MediaType.APPLICATION_JSON})
 *     public Response post(&#64;PathParam("callingEnvironment") String environment,
 *                          String body,
 *                          &#64;Context HttpHeaders headers) {
 *         Environment env = Environment.parse(environment);          // 404 on an unknown value
 *         Supplier&lt;ExecutionInput.Builder&gt; input = () -&gt;       // runs when an operation executes
 *             Graphitron.newOwnedExecutionInput(sessionFor(authenticator.login(headers, env), env));
 *         return env.isProduction()
 *             ? handler.post(body, headers, input)                   // no policy: every operation
 *             : handler.post(body, headers, QUERIES_ONLY, input);    // 400 before execution
 *     }
 *
 *     &#64;GET
 *     &#64;Produces({GraphqlHttpHandler.GRAPHQL_RESPONSE_JSON, MediaType.APPLICATION_JSON})
 *     public Response get(&#64;PathParam("callingEnvironment") String environment,
 *                         &#64;QueryParam("query") String query,
 *                         &#64;QueryParam("operationName") String operationName,
 *                         &#64;QueryParam("variables") String variables,
 *                         &#64;QueryParam("extensions") String extensions,
 *                         &#64;Context HttpHeaders headers) {
 *         Environment env = Environment.parse(environment);
 *         Supplier&lt;ExecutionInput.Builder&gt; input = () -&gt;
 *             Graphitron.newOwnedExecutionInput(sessionFor(authenticator.login(headers, env), env));
 *         // Parameter order matches the four &#64;QueryParam names directly above. GET carries the
 *         // specification's queries-only rule (405 + Allow: POST), with no policy argument to pass.
 *         return handler.get(query, operationName, variables, extensions, headers, input);
 *     }
 * }
 * </pre>
 *
 * <p>Properties that sketch depends on:
 *
 * <ul>
 *   <li><b>The input supplier runs inside the guard.</b> It is called on the request thread only
 *       when an operation executes, after the policy has judged the operation, so a request
 *       answered earlier never calls it. What it builds carries the request's values, read where
 *       the request is in hand, so no request-scoped bean needs to carry them between the resource
 *       and the engine. The overloads without a supplier build the input with
 *       {@link GraphitronApplication#newExecutionInput()}, the built-in endpoint's seam.</li>
 *   <li><b>Client faults propagate.</b> A {@link WebApplicationException} thrown by the resource
 *       method, or by the supplier, reaches the container unredacted. That is the documented way to
 *       answer {@code 401} and {@code 403}. A rejected session identity reaches
 *       {@link GraphitronApplication#rejectedIdentity(Throwable)} with the mount's own exception,
 *       and {@link GraphitronApplication#clientFault(Throwable)} may answer any other escape the
 *       same way; the rest are redacted to a reference-only {@code 500}.</li>
 *   <li><b>Mounting under {@code /graphql/{...}} shadows the built-in sub-paths.</b> Jakarta REST
 *       sorts candidate root resource classes by literal-character count, and {@code /graphql/} has
 *       one more literal character than {@code /graphql}, so the templated class wins every request
 *       with two or more segments. {@code /graphql/schema} and {@code /graphql/assets/graphiql.js}
 *       then reach the consumer's class, whatever
 *       {@link GraphitronApplication#builtInEndpointEnabled()} says. A consumer mounting there
 *       serves the assets, and the SDL, from its own resource, which returning a
 *       {@link GraphqlEndpoint.Request} does without writing the arms.</li>
 *   <li><b>The two verbs carry their rules differently.</b> POST takes the policy the consumer
 *       selects per request; GET carries the specification's {@code 405} rule unconditionally. That
 *       is why there is one {@code get} and two {@code post}s per way of building the input, and no
 *       entry point that runs a pre-parsed request with no rule attached.</li>
 * </ul>
 */
@ApplicationScoped
public class GraphqlHttpHandler {

    /** The modern GraphQL-over-HTTP response media type. */
    public static final String GRAPHQL_RESPONSE_JSON = "application/graphql-response+json";

    private static final Logger LOGGER = LoggerFactory.getLogger(GraphqlHttpHandler.class);

    private static final MediaType GRAPHQL_RESPONSE_TYPE =
        new MediaType("application", "graphql-response+json");

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

    /**
     * Executes a POST body with no operation-type restriction: whatever the document resolves to
     * runs, subject to the schema. The input is built by {@link GraphitronApplication#newExecutionInput()}.
     *
     * @param body    the raw request body, expected to be a JSON object carrying {@code query}
     * @param headers this request's headers, read for {@code Accept} content negotiation
     * @return the GraphQL-over-HTTP response, status and media type included
     */
    public Response post(String body, HttpHeaders headers) {
        return doPost(body, headers, null, application::newExecutionInput);
    }

    /**
     * Executes a POST body under {@code policy}: a document resolving to an operation the policy
     * refuses is answered with the policy's status <em>before</em> execution begins. The input is
     * built by {@link GraphitronApplication#newExecutionInput()}.
     *
     * <p>Passing {@code null} is the unrestricted state and is exactly what
     * {@link #post(String, HttpHeaders)} does; write that instead, since a literal {@code null}
     * no longer compiles against the overloads that take an input supplier. It is not equivalent to
     * a policy that happens to permit every operation type: an unrestricted request is never
     * pre-parsed, so a syntactically invalid document reaches the engine and comes back as
     * graphql-java's {@code InvalidSyntax} result, where a policy-carrying request answers this
     * library's parse-failure request error. Both are {@code 400}; the bodies differ.
     *
     * @param body    the raw request body, expected to be a JSON object carrying {@code query}
     * @param headers this request's headers, read for {@code Accept} content negotiation
     * @param policy  the operation-type policy, or {@code null} for no restriction
     * @return the GraphQL-over-HTTP response, status and media type included
     */
    public Response post(String body, HttpHeaders headers, OperationPolicy policy) {
        return doPost(body, headers, policy, application::newExecutionInput);
    }

    /**
     * Executes a POST body with no operation-type restriction, building the input with
     * {@code input} instead of {@link GraphitronApplication#newExecutionInput()}. The supplier runs
     * on the request thread only when an operation executes, inside the guard that passes a
     * {@link WebApplicationException} through unredacted, so a request that is answered earlier
     * (an empty body, an unparseable request) never calls it.
     *
     * @param body    the raw request body, expected to be a JSON object carrying {@code query}
     * @param headers this request's headers, read for {@code Accept} content negotiation
     * @param input   builds this request's execution input; must not be {@code null}
     * @return the GraphQL-over-HTTP response, status and media type included
     */
    public Response post(String body, HttpHeaders headers, Supplier<ExecutionInput.Builder> input) {
        return doPost(body, headers, null, Objects.requireNonNull(input, "input"));
    }

    /**
     * Executes a POST body under {@code policy}, building the input with {@code input}; the
     * counterpart of {@link #post(String, HttpHeaders, Supplier)} for a restricted endpoint.
     *
     * @param body    the raw request body, expected to be a JSON object carrying {@code query}
     * @param headers this request's headers, read for {@code Accept} content negotiation
     * @param policy  the operation-type policy; must not be {@code null}
     * @param input   builds this request's execution input; must not be {@code null}
     * @return the GraphQL-over-HTTP response, status and media type included
     */
    public Response post(String body, HttpHeaders headers, OperationPolicy policy,
                          Supplier<ExecutionInput.Builder> input) {
        return doPost(body, headers, Objects.requireNonNull(policy, "policy"),
            Objects.requireNonNull(input, "input"));
    }

    private Response doPost(String body, HttpHeaders headers, OperationPolicy policy,
                            Supplier<ExecutionInput.Builder> input) {
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
        return execute(request, policy, legacy, input);
    }

    /**
     * Executes a GET request from its query-string parameters. GET always carries the
     * GraphQL-over-HTTP specification's queries-only rule, answering {@code 405} with
     * {@code Allow: POST} for a document resolving to a mutation or subscription, so there is no
     * policy argument: letting a consumer weaken this would let a consumer break conformance. A
     * consumer that wants no GraphQL over GET declares no {@code @GET} method.
     *
     * <p>The four leading parameters are positional here where {@code @QueryParam} makes them
     * order-independent in a resource, so keep the forwarding call directly under the annotated
     * parameters it mirrors: a transposed {@code variables}/{@code extensions} pair compiles.
     *
     * @param query         the {@code query} parameter, the GraphQL document; required
     * @param operationName the {@code operationName} parameter; may be {@code null}
     * @param variables     the {@code variables} parameter, a JSON object; may be {@code null}
     * @param extensions    the {@code extensions} parameter, a JSON object; may be {@code null}
     * @param headers       this request's headers, read for {@code Accept} content negotiation
     * @return the GraphQL-over-HTTP response, status and media type included
     */
    public Response get(String query, String operationName, String variables, String extensions,
                        HttpHeaders headers) {
        return doGet(query, operationName, variables, extensions, headers, application::newExecutionInput);
    }

    /**
     * Executes a GET request as {@link #get(String, String, String, String, HttpHeaders)} does,
     * building the input with {@code input} instead of
     * {@link GraphitronApplication#newExecutionInput()}; the supplier runs only when an operation
     * executes. GET still carries the queries-only rule unconditionally.
     *
     * @param query         the {@code query} parameter, the GraphQL document; required
     * @param operationName the {@code operationName} parameter; may be {@code null}
     * @param variables     the {@code variables} parameter, a JSON object; may be {@code null}
     * @param extensions    the {@code extensions} parameter, a JSON object; may be {@code null}
     * @param headers       this request's headers, read for {@code Accept} content negotiation
     * @param input         builds this request's execution input; must not be {@code null}
     * @return the GraphQL-over-HTTP response, status and media type included
     */
    public Response get(String query, String operationName, String variables, String extensions,
                        HttpHeaders headers, Supplier<ExecutionInput.Builder> input) {
        return doGet(query, operationName, variables, extensions, headers,
            Objects.requireNonNull(input, "input"));
    }

    private Response doGet(String query, String operationName, String variables, String extensions,
                           HttpHeaders headers, Supplier<ExecutionInput.Builder> input) {
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
        return execute(new GraphqlRequest(query, operationName, variableMap, extensionMap),
            OperationPolicy.SPEC_GET, legacy, input);
    }

    /**
     * The schema as SDL, for an endpoint serving it as {@code text/plain}. Printed by
     * {@link GraphitronApplication#schemaSdl()} from the same {@link GraphitronApplication#schema()}
     * the engine executes, so the two cannot drift.
     *
     * @return the printed SDL
     */
    public String schema() {
        return application.schemaSdl();
    }

    /**
     * Which operation a document will run: the one named by {@code operationName} when one is
     * given, otherwise the first in the document. Public because it is a decision a consumer may
     * legitimately want to take for itself (metrics, logging, its own routing), and because the
     * pipeline calls exactly this method, so what a consumer observes is what the guard judged.
     *
     * <p>The resolved operation is the right unit for a policy precisely because the engine honours
     * {@code operationName}: a document carrying a query and a mutation runs whichever the request
     * selects, so judging the document as a whole would refuse a legitimate request. Every
     * unresolvable case ({@code null} below) is one graphql-java itself answers with a request
     * error before execution, so falling through to the engine cannot smuggle an operation past a
     * policy. {@code OperationGuardTest} pins each of those cases.
     *
     * <p>This is deliberately the library's own resolution rather than a call into graphql-java's:
     * the engine resolves through a type its authors annotate as internal, and this decision now
     * carries a consumer's trust boundary.
     *
     * @param query         the GraphQL document
     * @param operationName the requested operation name; may be {@code null} or blank
     * @return the resolved operation type, or {@code null} when none resolves (no operation
     *         definitions, or an {@code operationName} matching none)
     * @throws graphql.parser.InvalidSyntaxException if the document cannot be parsed
     */
    public static OperationDefinition.Operation resolveOperation(String query, String operationName) {
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

    /**
     * The single execution path for both verbs: apply the operation guard when there is one, build
     * the input via the seam, execute, and shape the response (status + media type + serialised
     * body).
     *
     * <p>{@code guard} is nullable and only the library constructs the argument, which is what keeps
     * "no rule attached" unreachable from a consumer: GET is routed here with
     * {@link OperationPolicy#SPEC_GET} and POST with whatever the consumer chose or nothing.
     */
    private Response execute(GraphqlRequest request, OperationPolicy guard, boolean legacy,
                             Supplier<ExecutionInput.Builder> input) {
        if (guard != null) {
            OperationDefinition.Operation operation;
            try {
                operation = resolveOperation(request.query(), request.operationName());
            } catch (Exception parseFailure) {
                // The GraphQL document cannot be parsed -> 400.
                return requestError(400, "The GraphQL document could not be parsed.", legacy);
            }
            if (operation != null && !guard.permits(operation)) {
                // Not downgraded under legacy application/json: this is an HTTP-level rule, not a
                // GraphQL request error. Only the body's media type follows negotiation.
                OperationPolicy.Rejection rejection = guard.rejectionFor(operation);
                Response.ResponseBuilder rejected = Response.status(rejection.status())
                    .type(responseType(legacy))
                    .entity(serialise(errorBody(rejection.message())));
                rejection.headers().forEach(rejected::header);
                return rejected.build();
            }
        }

        // The input-building seam (the supplier: the application's newExecutionInput(), or the one a
        // resource hands over per request) and the engine execution both run under one ordered
        // two-arm guard. The
        // arm order is load-bearing: WebApplicationException is a RuntimeException, so the broad
        // second arm would otherwise swallow it (see the WebApplicationException passthrough below).
        try {
            ExecutionInput.Builder builder = Objects.requireNonNull(input.get(), "the input supplier returned null")
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
        } catch (WebApplicationException clientFault) {
            // The auth-seeded seam, or the consumer's own resource method, can throw a
            // WebApplicationException (e.g. NotAuthorizedException -> 401, ForbiddenException ->
            // 403) to signal a client-facing 4xx. Re-throw it unredacted: this catch runs inside
            // the pipeline, so JAX-RS only maps the status the consumer chose if the exception
            // propagates out. The seam, not a swallow, carries the 4xx.
            throw clientFault;
        } catch (Exception thrown) {
            // A rejected session mount reaches the application's rejectedIdentity first, with the
            // mount's own exception; then clientFault may answer any escape as a client fault, the
            // shape the arm above passes through (a 401, say). Nothing is logged when either does.
            Optional<WebApplicationException> rejected = rejectedIdentity(thrown);
            if (rejected.isPresent()) {
                throw rejected.get();
            }
            Optional<WebApplicationException> clientFault = clientFault(thrown);
            if (clientFault.isPresent()) {
                throw clientFault.get();
            }
            // Any other escape is a genuine internal fault (the observed case: the seam forcing a
            // JDBC connection with the database down). This handler-level guard is the structural
            // complement to the generated ErrorRouter's per-fetcher redaction: newExecutionInput()
            // runs before graphql-java execution begins, so neither ErrorRouter nor graphql-java's
            // own exception handling can ever see a fault thrown here. Redact to the same
            // reference-only wire shape ErrorRouter.redact emits, so both sites present one contract:
            // log the real cause under a reference, return no exception message/class/stack. The
            // application chooses the reference and the text, as FetcherErrorResponse does for a
            // fetcher; a failure of either override falls back to the default.
            String reference = newReference(thrown);
            LOGGER.error("Uncaught exception building/executing GraphQL request; correlation id = {}",
                reference, thrown);
            return Response.status(legacy ? 200 : 500)
                .type(responseType(legacy))
                .entity(serialise(errorBody(redactedMessage(thrown, reference))))
                .build();
        }
    }

    /**
     * {@link GraphitronApplication#mountCause(Throwable)} then
     * {@link GraphitronApplication#rejectedIdentity(Throwable)}, contained: empty (so redaction or
     * {@code clientFault}) when there is no mount failure or either override fails.
     */
    private Optional<WebApplicationException> rejectedIdentity(Throwable thrown) {
        Optional<Throwable> cause;
        try {
            cause = application.mountCause(thrown);
        } catch (Throwable failure) {
            logOverrideFailure("mountCause", failure, "redacting");
            return Optional.empty();
        }
        if (cause == null) {
            logOverrideFailure("mountCause", null, "redacting");
            return Optional.empty();
        }
        if (cause.isEmpty()) {
            return Optional.empty();
        }
        Optional<WebApplicationException> answer;
        try {
            answer = application.rejectedIdentity(cause.get());
        } catch (Throwable failure) {
            logOverrideFailure("rejectedIdentity", failure, "redacting");
            return Optional.empty();
        }
        if (answer == null) {
            logOverrideFailure("rejectedIdentity", null, "redacting");
            return Optional.empty();
        }
        return answer;
    }

    /** {@link GraphitronApplication#clientFault(Throwable)}, contained: redaction on failure. */
    private Optional<WebApplicationException> clientFault(Throwable thrown) {
        Optional<WebApplicationException> answer;
        try {
            answer = application.clientFault(thrown);
        } catch (Throwable failure) {
            logOverrideFailure("clientFault", failure, "redacting");
            return Optional.empty();
        }
        if (answer == null) {
            logOverrideFailure("clientFault", null, "redacting");
            return Optional.empty();
        }
        return answer;
    }

    /** {@link GraphitronApplication#newReference(Throwable)}, contained: the default on failure. */
    private String newReference(Throwable thrown) {
        String reference;
        try {
            reference = application.newReference(thrown);
        } catch (Throwable failure) {
            logOverrideFailure("newReference", failure, "no reference yet");
            return RedactionDefaults.reference();
        }
        if (reference == null) {
            logOverrideFailure("newReference", null, "no reference yet");
            return RedactionDefaults.reference();
        }
        return reference;
    }

    /** {@link GraphitronApplication#redactedMessage(Throwable, String)}, contained: the default on failure. */
    private String redactedMessage(Throwable thrown, String reference) {
        String message;
        try {
            message = application.redactedMessage(thrown, reference);
        } catch (Throwable failure) {
            logOverrideFailure("redactedMessage", failure, "reference = " + reference);
            return RedactionDefaults.message(reference);
        }
        if (message == null) {
            logOverrideFailure("redactedMessage", null, "reference = " + reference);
            return RedactionDefaults.message(reference);
        }
        return message;
    }

    /**
     * The one ERROR line for an override that threw ({@code failure}) or returned {@code null}
     * ({@code failure == null}), naming the application's class and the class of what it threw,
     * without either exception's message; only a {@link VirtualMachineError} propagates.
     */
    private void logOverrideFailure(String method, Throwable failure, String subject) {
        if (failure instanceof VirtualMachineError) {
            throw (VirtualMachineError) failure;
        }
        if (failure == null) {
            LOGGER.error("{}.{} returned null; {}", application.getClass().getName(), method, subject);
        } else {
            LOGGER.error("{}.{} threw {}; {}", application.getClass().getName(), method,
                failure.getClass().getName(), subject);
        }
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
}
