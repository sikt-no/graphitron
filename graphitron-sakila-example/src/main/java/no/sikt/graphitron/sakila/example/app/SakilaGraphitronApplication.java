package no.sikt.graphitron.sakila.example.app;

import graphql.ExecutionInput;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
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

    @Inject
    AgroalDataSource dataSource;

    public SakilaGraphitronApplication() {
        super(() -> Graphitron.buildSchema(builder -> {}));
    }

    @Override
    public ExecutionInput.Builder newExecutionInput() {
        return Graphitron.newExecutionInput(DSL.using(dataSource, SQLDialect.POSTGRES), "test-user");
    }
}
