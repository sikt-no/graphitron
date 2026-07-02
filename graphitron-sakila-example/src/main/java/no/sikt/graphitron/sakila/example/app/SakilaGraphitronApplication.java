package no.sikt.graphitron.sakila.example.app;

import graphql.ExecutionInput;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import no.sikt.graphitron.generated.Graphitron;
import no.sikt.graphitron.jakarta.rest.AbstractGraphitronApplication;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;

/**
 * The reference subgraph's {@link no.sikt.graphitron.jakarta.rest.GraphitronApplication} adapter:
 * the only hand-written GraphQL-over-HTTP wiring in this module. Everything else (the
 * {@code /graphql} resource, the engine, status-code semantics, the {@code /schema} SDL endpoint,
 * and the GraphiQL page) comes from {@code graphitron-jakarta-rest}.
 *
 * <p>This is the SPI seam in practice: the schema is supplied as a lambda over the generated
 * {@code Graphitron} facade (the only generated-symbol reference the library never names), and
 * {@link #newExecutionInput()} binds a per-request {@code DSLContext} over the Quarkus-managed
 * datasource plus the schema's one {@code contextArgument} ({@code userId}). A real subgraph would
 * resolve {@code userId} from the authenticated request rather than hard-coding it.
 */
@ApplicationScoped
public class SakilaGraphitronApplication extends AbstractGraphitronApplication {

    /**
     * Test-only fault-injection header (R421). A request carrying {@code X-Graphitron-Fault}
     * exercises the resource's server-side-failure guard, the one path a normal query cannot reach,
     * by making this seam throw before execution begins. {@code internal} throws an ordinary
     * exception carrying internal-looking detail (the DB-down analogue the resource must redact);
     * {@code forbidden} throws a JAX-RS {@link ForbiddenException} the resource must let propagate so
     * the container maps it to 403. Present only in this reference adapter, never in the library.
     */
    static final String FAULT_HEADER = "X-Graphitron-Fault";

    @Inject
    AgroalDataSource dataSource;

    @Context
    HttpHeaders headers;

    public SakilaGraphitronApplication() {
        super(() -> Graphitron.buildSchema(builder -> {}));
    }

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
        return Graphitron.newExecutionInput(DSL.using(dataSource, SQLDialect.POSTGRES), "test-user");
    }
}
