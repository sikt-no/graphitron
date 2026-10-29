package no.sikt.graphitron.sakila.example.app;

import graphql.ExecutionInput;
import graphql.GraphQL;
import graphql.execution.instrumentation.Instrumentation;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import no.sikt.graphitron.generated.Graphitron;
import no.sikt.graphitron.jakarta.rest.AbstractGraphitronApplication;
import org.jooq.SQLDialect;
import org.jooq.conf.Settings;

/**
 * The reference subgraph's {@link no.sikt.graphitron.jakarta.rest.GraphitronApplication} adapter:
 * the only hand-written GraphQL-over-HTTP wiring in this module. Everything else (the
 * {@code /graphql} resource, the engine, status-code semantics, the {@code /schema} SDL endpoint,
 * and the GraphiQL page) comes from {@code graphitron-jakarta-rest}.
 *
 * <p>This is the SPI seam in practice, wired to the owned-connection path. The adapter reaches the
 * generated {@code Graphitron} facade only through method references (the schema, its SDL printer
 * for {@code /schema}, and the recogniser of a rejected session mount), which is why the library
 * never names a generated symbol. {@link #engineBuilder()} builds the engine from the
 * application-scoped {@code GraphitronRuntime} over the Quarkus-managed datasource and the jOOQ
 * {@code Settings} of the application, so graphitron takes one connection per operation, mounts the
 * caller's identity through the {@code SessionState} each input carries, and demarcates
 * transactions. {@link #newExecutionInput()} is the built-in endpoint's seam and carries only
 * per-request data: the session state, the claims it mounts and the schema's {@code userId}
 * {@code contextArgument}. A real subgraph would pass the authenticated request's token (e.g.
 * {@code jwt.getRawToken()}) and resolve {@code userId} from it rather than hard-coding both, and
 * one that serves GraphQL from a resource of its own would build that input there and hand it to
 * {@code GraphqlEndpoint} instead of overriding this method.
 */
@ApplicationScoped
public class SakilaGraphitronApplication extends AbstractGraphitronApplication {

    @Inject
    AgroalDataSource dataSource;

    public SakilaGraphitronApplication() {
        super(Graphitron::buildSchema, Graphitron::schemaSdl, Graphitron::sessionMountFailure);
    }

    @Override
    public GraphQL.Builder engineBuilder() {
        return engine();
    }

    /**
     * The owned-connection engine, with {@code extra} instrumentations chained after graphitron's
     * own, which stays first: nothing here names it or a chained instrumentation. Called once at
     * application scope (after CDI injection), so the runtime is built exactly once and lives
     * inside the cached engine's instrumentation.
     */
    protected GraphQL.Builder engine(Instrumentation... extra) {
        return Graphitron.runtime(dataSource, SQLDialect.POSTGRES, new Settings()).newGraphQL(schema(), extra);
    }

    @Override
    public ExecutionInput.Builder newExecutionInput() {
        String claims = "{\"sub\":\"test-user\"}";
        return Graphitron.newOwnedExecutionInput(SakilaSessions.of(claims), claims, "test-user");
    }
}
