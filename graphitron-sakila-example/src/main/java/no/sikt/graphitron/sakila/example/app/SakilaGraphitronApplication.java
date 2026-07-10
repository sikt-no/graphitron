package no.sikt.graphitron.sakila.example.app;

import graphql.ExecutionInput;
import graphql.GraphQL;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import no.sikt.graphitron.generated.Graphitron;
import no.sikt.graphitron.jakarta.rest.AbstractGraphitronApplication;
import org.jooq.SQLDialect;

/**
 * The reference subgraph's {@link no.sikt.graphitron.jakarta.rest.GraphitronApplication} adapter:
 * the only hand-written GraphQL-over-HTTP wiring in this module. Everything else (the
 * {@code /graphql} resource, the engine, status-code semantics, the {@code /schema} SDL endpoint,
 * and the GraphiQL page) comes from {@code graphitron-jakarta-rest}.
 *
 * <p>This is the SPI seam in practice, wired to the owned-connection path (R429): the schema is
 * supplied as a lambda over the generated {@code Graphitron} facade (the only generated-symbol
 * reference the library never names), {@link #engineBuilder()} builds the engine from the
 * application-scoped {@code GraphitronRuntime} over the Quarkus-managed datasource so graphitron
 * pins one connection per operation, mounts the caller's identity through the configured
 * {@code <sessionState>} hook, and demarcates transactions, and {@link #newExecutionInput()}
 * carries only per-request data: the opaque claims payload plus the schema's one
 * {@code contextArgument} ({@code userId}). A real subgraph would pass the authenticated request's
 * token (e.g. {@code jwt.getRawToken()}, or its decoded claims segment for the {@code <variables>}
 * sugar configured here) and resolve {@code userId} from it rather than hard-coding both.
 */
@ApplicationScoped
public class SakilaGraphitronApplication extends AbstractGraphitronApplication {

    @Inject
    AgroalDataSource dataSource;

    public SakilaGraphitronApplication() {
        super(() -> Graphitron.buildSchema(builder -> {}));
    }

    @Override
    public GraphQL.Builder engineBuilder() {
        // Called once at application scope (after CDI injection), so the runtime is built exactly
        // once and lives inside the cached engine's instrumentation.
        return Graphitron.runtime(dataSource, SQLDialect.POSTGRES).newGraphQL(schema());
    }

    @Override
    public ExecutionInput.Builder newExecutionInput() {
        return Graphitron.newOwnedExecutionInput("{\"sub\":\"test-user\"}", "test-user");
    }
}
