package no.sikt.graphitron.sakila.example.app;

import graphql.ExecutionInput;
import graphql.GraphQL;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.NotAuthorizedException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import no.sikt.graphitron.generated.Graphitron;
import no.sikt.graphitron.generated.schema.SessionMountException;

import java.util.Optional;

/**
 * Test-only fault-injection seam for the error-redaction guard. The shipped reference adapter
 * ({@link SakilaGraphitronApplication}) is copy-paste template for real subgraphs, so it stays
 * pristine; this {@code @Alternative} lives in test source and is selected only during the module's
 * {@code @QuarkusTest} run (via {@link Priority}). It subclasses the real adapter, so
 * {@link no.sikt.graphitron.jakarta.rest.GraphqlHttpHandler} still drives the real seam wiring
 * (inherited {@code dataSource}, {@code super.newExecutionInput()}); only the fault branches are added.
 *
 * <p>A request carrying {@link #FAULT_HEADER} makes {@code newExecutionInput()} throw before execution
 * begins, the one region a normal query cannot reach. {@code internal} throws an ordinary exception
 * carrying internal-looking detail (the DB-down analogue the handler must redact); {@code forbidden}
 * throws a JAX-RS {@link ForbiddenException} the handler must let propagate so the container maps it
 * to 403.
 *
 * <p>Only one {@code @Alternative} adapter can be selected, so every test-only SPI behaviour this
 * module needs lands here rather than in a second class. Besides the fault branches that name it,
 * that means recording what {@code newExecutionInput()} observed in {@link CallingEnvironment} (the
 * ordering property {@link MountedEndpointTest} pins) and reading
 * {@link #builtInEndpointEnabled()} off a request header, so a test can turn the built-in endpoint
 * off without a second Quarkus boot. A request carrying {@link #TRACE_HEADER} gets the traced
 * reference and text of {@link TracedErrorText} at both redaction sites: through the
 * {@link #newReference} / {@link #redactedMessage} overrides here, and through the generated
 * {@code FetcherErrorResponse} this seam puts into the request's context.
 */
@Alternative
@Priority(1)
@ApplicationScoped
public class FaultInjectingGraphitronApplication extends SakilaGraphitronApplication {

    /** Sentinel header the conformance suite sends to drive a server-side seam fault. */
    static final String FAULT_HEADER = "X-Graphitron-Fault";

    /** Sentinel header carrying a trace id both redaction sites quote as the reference. */
    static final String TRACE_HEADER = "X-Graphitron-Trace";

    /**
     * Sentinel header seeding the request with the principal the database's mount routine rejects,
     * and choosing what {@link #clientFault} answers: {@code 401} maps the rejection to
     * {@code NotAuthorizedException}, {@code throw} and {@code null} misbehave, {@code none} keeps
     * the default.
     */
    static final String REJECTED_IDENTITY_HEADER = "X-Graphitron-Rejected-Identity";

    /** Sentinel header turning the built-in {@code /graphql} endpoint off for one request. */
    static final String BUILT_IN_HEADER = "X-Graphitron-Built-In";

    /** {@code off} answers {@code 404} for the GraphiQL page and its assets, built-in or mounted. */
    static final String GRAPHIQL_HEADER = "X-Graphitron-Graphiql";

    /** The rejected-identity hook's modes; see {@link #rejectedIdentity(Throwable)}. */
    static final String HOOK_ANSWERS = "hook";
    static final String HOOK_DECLINES = "hook-declines";

    /** Which of the two hooks answered a rejected identity, and the class of the cause it was given. */
    static final String ANSWERED_BY_HEADER = "X-Answered-By";
    static final String CAUSE_CLASS_HEADER = "X-Cause-Class";

    @Context
    HttpHeaders headers;

    @Inject
    CallingEnvironment environment;

    /**
     * Per-request rather than per-deployment, which is what lets one test class cover both states.
     * That is a property of this fixture, not evidence about the toggle's design: the library
     * evaluates it on every request because it is a 404 gate on a registered route.
     */
    @Override
    public boolean builtInEndpointEnabled() {
        return !"off".equals(headers.getHeaderString(BUILT_IN_HEADER));
    }

    /**
     * The engine with a recording instrumentation chained after graphitron's, so every test in this
     * module also runs with an extra instrumentation present; {@link EngineAssemblyTest} reads what
     * it recorded.
     */
    @Override
    public GraphQL.Builder engineBuilder() {
        return engine(RecordingInstrumentation.INSTANCE);
    }

    @Override
    public boolean graphiqlEnabled() {
        return !"off".equals(headers.getHeaderString(GRAPHIQL_HEADER));
    }

    /**
     * With {@link #HOOK_ANSWERS} as the rejected-identity mode, answers a mount failure with a 401
     * naming this hook and the class of the exception it was given; {@code clientFault} below would
     * answer the same failure with a 403, so which status arrives shows which hook ran. Any other
     * mode declines, leaving the decision to {@code clientFault}.
     */
    @Override
    public Optional<WebApplicationException> rejectedIdentity(Throwable mountCause) {
        if (HOOK_ANSWERS.equals(headers.getHeaderString(REJECTED_IDENTITY_HEADER))) {
            return Optional.of(new WebApplicationException(Response.status(401)
                .header(ANSWERED_BY_HEADER, "rejectedIdentity")
                .header(CAUSE_CLASS_HEADER, mountCause.getClass().getName())
                .build()));
        }
        return super.rejectedIdentity(mountCause);
    }

    @Override
    public ExecutionInput.Builder newExecutionInput() {
        // Runs after the resource method that delegated here, so whatever that method wrote into the
        // request-scoped holder is visible. The mounted fixture echoes this back as a header.
        environment.recordSeamObservation();
        String fault = headers.getHeaderString(FAULT_HEADER);
        if ("forbidden".equals(fault)) {
            // A client-facing 4xx raised while seeding the request: the handler must re-throw this
            // unredacted so JAX-RS maps it to 403, not collapse it to a redacted 500.
            throw new ForbiddenException("test-user is not permitted to seed this request");
        }
        if ("internal".equals(fault)) {
            // A genuine internal fault analogous to the observed DB-down CreationException. The
            // message carries host/port and a package name the redaction must not leak to the client.
            throw new IllegalStateException(
                "could not open JDBC connection to db-fault-host:5432 in no.sikt.graphitron.internal");
        }
        if (headers.getHeaderString(REJECTED_IDENTITY_HEADER) != null) {
            return Graphitron.newOwnedExecutionInput(no.sikt.graphitron.sakila.example.app.SakilaSessions.of("{\"sub\":\"reject-me\"}"), "{\"sub\":\"reject-me\"}", "reject-me");
        }
        String traceId = headers.getHeaderString(TRACE_HEADER);
        if (traceId != null) {
            return super.newExecutionInput().graphQLContext(b -> b
                .put(no.sikt.graphitron.generated.schema.FetcherErrorResponse.class, TracedErrorText.RESPONSE)
                .put(TracedErrorText.TRACE_KEY, traceId));
        }
        return super.newExecutionInput();
    }

    @Override
    public Optional<WebApplicationException> clientFault(Throwable thrown) {
        String mode = headers.getHeaderString(REJECTED_IDENTITY_HEADER);
        if (mode == null || "none".equals(mode)) {
            return super.clientFault(thrown);
        }
        if ("throw".equals(mode)) {
            throw new IllegalStateException("the adapter's clientFault is broken");
        }
        if ("null".equals(mode)) {
            return null;
        }
        if (HOOK_ANSWERS.equals(mode) || HOOK_DECLINES.equals(mode)) {
            // Reached for a mount failure only when rejectedIdentity declined (or never ran); the 403
            // distinguishes this hook's answer from rejectedIdentity's 401.
            return Optional.of(new WebApplicationException(Response.status(403)
                .header(ANSWERED_BY_HEADER, "clientFault")
                .build()));
        }
        if (thrown instanceof SessionMountException mount && mount.getCause() != null) {
            return Optional.of(new NotAuthorizedException("identity rejected", "Bearer"));
        }
        return Optional.empty();
    }

    @Override
    public String newReference(Throwable thrown) {
        String traceId = headers.getHeaderString(TRACE_HEADER);
        return traceId != null ? traceId : super.newReference(thrown);
    }

    @Override
    public String redactedMessage(Throwable thrown, String reference) {
        return headers.getHeaderString(TRACE_HEADER) != null
            ? TracedErrorText.message(reference)
            : super.redactedMessage(thrown, reference);
    }
}
