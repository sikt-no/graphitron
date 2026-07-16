package no.sikt.graphitron.sakila.example.app;

import graphql.ExecutionInput;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;

/**
 * Test-only fault-injection seam for the error-redaction guard. The shipped reference adapter
 * ({@link SakilaGraphitronApplication}) is copy-paste template for real subgraphs, so it stays
 * pristine; this {@code @Alternative} lives in test source and is selected only during the module's
 * {@code @QuarkusTest} run (via {@link Priority}). It subclasses the real adapter, so
 * {@link no.sikt.graphitron.jakarta.rest.GraphqlResource#execute} still drives the real seam wiring
 * (inherited {@code dataSource}, {@code super.newExecutionInput()}); only the fault branches are added.
 *
 * <p>A request carrying {@link #FAULT_HEADER} makes {@code newExecutionInput()} throw before execution
 * begins, the one region a normal query cannot reach. {@code internal} throws an ordinary exception
 * carrying internal-looking detail (the DB-down analogue the resource must redact); {@code forbidden}
 * throws a JAX-RS {@link ForbiddenException} the resource must let propagate so the container maps it
 * to 403.
 */
@Alternative
@Priority(1)
@ApplicationScoped
public class FaultInjectingGraphitronApplication extends SakilaGraphitronApplication {

    /** Sentinel header the conformance suite sends to drive a server-side seam fault. */
    static final String FAULT_HEADER = "X-Graphitron-Fault";

    @Context
    HttpHeaders headers;

    @Override
    public ExecutionInput.Builder newExecutionInput() {
        String fault = headers.getHeaderString(FAULT_HEADER);
        if ("forbidden".equals(fault)) {
            // A client-facing 4xx raised while seeding the request: the resource must re-throw this
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
