package no.sikt.graphitron.jakarta.rest;

import graphql.language.OperationDefinition.Operation;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Which GraphQL operation types an endpoint accepts, and how it refuses the rest. A configuration
 * value passed per call to
 * {@link GraphqlHttpHandler#post(String, HttpHeaders, OperationPolicy)}, not a mode the handler is
 * put into: a consumer selecting per request writes the selection as the call.
 *
 * <pre>{@code
 * private static final OperationPolicy QUERIES_ONLY =
 *     OperationPolicy.queriesOnly(Response.Status.BAD_REQUEST);
 *
 * return env.isProduction() ? handler.post(body, headers)
 *                           : handler.post(body, headers, QUERIES_ONLY);
 * }</pre>
 *
 * <p>The check runs <em>before</em> execution, against the operation the engine will actually run
 * (see {@link GraphqlHttpHandler#resolveOperation(String, String)}), so a document carrying both a
 * query and a mutation is judged on the one {@code operationName} selects. A rejection carries this
 * policy's status for modern and legacy clients alike: it is an HTTP-level rule rather than a
 * GraphQL request error, so it is not downgraded to {@code 200} under legacy
 * {@code application/json}. Only the error body's media type follows content negotiation.
 *
 * <p>There is no permissive policy value. Passing no policy at all is the unrestricted state, which
 * is what the two-argument {@code post} overload expresses; see {@code post}'s javadoc for why the
 * two differ in more than permission semantics.
 *
 * <p>The policy governs the verbs a consumer routes through it. GET carries the GraphQL-over-HTTP
 * specification's queries-only rule ({@code 405} plus {@code Allow: POST}) unconditionally and takes
 * no policy argument, so a consumer mounting both verbs enforces "no mutation runs here" through two
 * paths with different statuses. That is sound, the GET rule being the stricter of the two, but it
 * is worth knowing before reading a 405 in a log.
 *
 * <p>Instances have identity equality. The rejection wording is not part of any comparison, and two
 * separately configured policies are not interchangeable just because their permitted sets match.
 */
public final class OperationPolicy {

    /**
     * The specification's rule for GET, expressed as one more instance of this type so there is a
     * single enforcement path rather than a hardcoded branch beside a configurable one. Not
     * published: a consumer cannot weaken conformance by passing a different rule for GET, because
     * GET takes no policy argument at all.
     */
    static final OperationPolicy SPEC_GET = new OperationPolicy(
        EnumSet.of(Operation.QUERY),
        Response.Status.METHOD_NOT_ALLOWED,
        null,
        Map.of(HttpHeaders.ALLOW, "POST"),
        "GraphQL %s operations must use POST, not GET.");

    private final Set<Operation> allowed;
    private final Response.StatusType status;
    private final String message;
    private final Map<String, String> headers;
    private final String messageTemplate;

    private OperationPolicy(Set<Operation> allowed,
                            Response.StatusType status,
                            String message,
                            Map<String, String> headers,
                            String messageTemplate) {
        this.allowed = allowed;
        this.status = status;
        this.message = message;
        this.headers = headers;
        this.messageTemplate = messageTemplate;
    }

    /**
     * Permits query operations only, refusing mutations and subscriptions with {@code status} and
     * this library's wording, which names the operation type that was actually rejected
     * ("GraphQL subscription operations are not supported on this endpoint."). Prefer this over the
     * two-argument form unless the endpoint needs its own phrasing: a fixed consumer string written
     * for mutations reads wrong the first time a subscription arrives.
     *
     * @param status the rejection status; must be in the {@code CLIENT_ERROR} or
     *               {@code SERVER_ERROR} family
     * @return a policy permitting {@link Operation#QUERY} only
     * @throws IllegalArgumentException if {@code status} is not a client or server error
     * @throws NullPointerException     if {@code status} is {@code null}
     */
    public static OperationPolicy queriesOnly(Response.StatusType status) {
        return allowing(EnumSet.of(Operation.QUERY), status, null);
    }

    /**
     * Permits query operations only, refusing mutations and subscriptions with {@code status} and
     * the given message.
     *
     * @param status  the rejection status; must be in the {@code CLIENT_ERROR} or
     *                {@code SERVER_ERROR} family
     * @param message the rejection message, returned as the sole {@code errors[0].message}
     * @return a policy permitting {@link Operation#QUERY} only
     * @throws IllegalArgumentException if {@code status} is not a client or server error, or
     *                                  {@code message} is blank
     * @throws NullPointerException     if {@code status} is {@code null}
     */
    public static OperationPolicy queriesOnly(Response.StatusType status, String message) {
        return allowing(EnumSet.of(Operation.QUERY), status, message);
    }

    /**
     * The general case, of which {@link #queriesOnly(Response.StatusType)} is the specialisation:
     * permits exactly {@code allowed} and refuses everything else. The policy this exists for is
     * queries and mutations permitted, subscriptions refused, which is the shape of a read-write
     * endpoint on a library that serves no subscription transport.
     *
     * @param allowed the permitted operation types; must be non-empty. Copied defensively, so a
     *                later change to the caller's set does not change this policy
     * @param status  the rejection status; must be in the {@code CLIENT_ERROR} or
     *                {@code SERVER_ERROR} family. Both families are open, so {@code 501} stays
     *                available for "this endpoint serves no subscription transport"
     * @param message the rejection message, or {@code null} for this library's wording, which
     *                interpolates the operation type that was rejected
     * @return a policy permitting exactly {@code allowed}
     * @throws IllegalArgumentException if {@code allowed} is empty, {@code status} is not a client
     *                                  or server error, or {@code message} is blank
     * @throws NullPointerException     if {@code allowed} or {@code status} is {@code null}
     */
    public static OperationPolicy allowing(Set<Operation> allowed,
                                           Response.StatusType status,
                                           String message) {
        if (allowed == null) {
            throw new NullPointerException("allowed operation types must not be null");
        }
        if (allowed.isEmpty()) {
            // An empty set refuses every operation, including the ones no client can avoid sending.
            // That is an endpoint that should not be mounted, not a policy.
            throw new IllegalArgumentException("allowed operation types must not be empty");
        }
        if (status == null) {
            throw new NullPointerException("rejection status must not be null");
        }
        Response.Status.Family family = status.getFamily();
        if (family != Response.Status.Family.CLIENT_ERROR && family != Response.Status.Family.SERVER_ERROR) {
            // StatusType constrains the type but not the value: without this check
            // queriesOnly(Response.Status.OK) compiles and answers a refusal no client can read
            // as one.
            throw new IllegalArgumentException(
                "rejection status must be a client or server error, was " + status.getStatusCode());
        }
        if (message != null && message.isBlank()) {
            throw new IllegalArgumentException("rejection message must not be blank");
        }
        return new OperationPolicy(EnumSet.copyOf(allowed), status, message, Map.of(),
            "GraphQL %s operations are not supported on this endpoint.");
    }

    /**
     * Whether this policy permits {@code operation}. The only question the policy is asked; the
     * permitted set is the factory's input and is deliberately not published back out.
     *
     * @param operation the resolved operation type
     * @return {@code true} if the operation may execute under this policy
     */
    public boolean permits(Operation operation) {
        return allowed.contains(operation);
    }

    /** The refusal for a non-permitted {@code operation}: status, body message, and any headers. */
    Rejection rejectionFor(Operation operation) {
        String rejection = message != null
            ? message
            : String.format(messageTemplate, operation.name().toLowerCase());
        return new Rejection(status, rejection, headers);
    }

    /**
     * A refusal on the wire. Not published: {@code headers} exists for the specification's
     * {@code Allow: POST} on GET and for nothing a consumer configures, and publishing it would put
     * an internal constant's shape in a Maven-Central artifact's API.
     *
     * @param status  the response status
     * @param message the sole {@code errors[0].message}
     * @param headers response headers the rule requires; empty for a consumer-configured policy
     */
    record Rejection(Response.StatusType status, String message, Map<String, String> headers) {
    }
}
