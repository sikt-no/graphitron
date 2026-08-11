package no.sikt.graphitron.sakila.example.app;

import graphql.ExecutionInput;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;

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
 * off without a second Quarkus boot.
 */
@Alternative
@Priority(1)
@ApplicationScoped
public class FaultInjectingGraphitronApplication extends SakilaGraphitronApplication {

    /** Sentinel header the conformance suite sends to drive a server-side seam fault. */
    static final String FAULT_HEADER = "X-Graphitron-Fault";

    /** Sentinel header turning the built-in {@code /graphql} endpoint off for one request. */
    static final String BUILT_IN_HEADER = "X-Graphitron-Built-In";

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
        return super.newExecutionInput();
    }
}
