package no.sikt.graphitron.rewrite.test.querydb;

import graphql.ExecutionInput;
import graphql.GraphQL;
import graphql.GraphQLContext;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.DataFetchingEnvironmentImpl;
import graphql.schema.GraphQLSchema;
import no.sikt.graphitron.generated.Graphitron;
import no.sikt.graphitron.generated.schema.GraphitronContext;
import no.sikt.graphitron.rewrite.test.tier.ExecutionTier;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.as;
import static org.assertj.core.api.InstanceOfAssertFactories.MAP;

/**
 * L6 execution-tier tests for R190's schema-driven {@code Graphitron.newExecutionInput} factory.
 * Two complementary cases:
 *
 * <ul>
 *   <li><b>Round-trip</b>: the schema's one {@code @service(contextArguments: ["userId"])} site
 *       ({@code Query.greetingByUser}) is queried, threading {@code userId} through the typed
 *       factory parameter. The {@code UserGreetingService.greet(String)} method receives the
 *       value and renders it into the response.</li>
 *   <li><b>Missing-value diagnostic</b>: hand-roll an {@code ExecutionInput.Builder} that
 *       bypasses the factory, omits the {@code userId} stash, and asserts the generated fetcher
 *       throws {@code IllegalStateException} naming the contextArgument and pointing at
 *       {@code Graphitron.newExecutionInput(...)}.</li>
 * </ul>
 */
@ExecutionTier
class FilmContextArgumentRoundTripTest {

    static PostgreSQLContainer postgres;
    static DSLContext dsl;
    static GraphQL graphql;

    @BeforeAll
    static void startDatabase() {
        String localUrl = System.getProperty("test.db.url");
        if (localUrl != null) {
            dsl = DSL.using(localUrl,
                System.getProperty("test.db.username", "postgres"),
                System.getProperty("test.db.password", "postgres"));
        } else {
            postgres = new PostgreSQLContainer("postgres:18-alpine").withInitScript("init.sql");
            postgres.start();
            dsl = DSL.using(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        }
        GraphQLSchema schema = Graphitron.buildSchema(b -> {});
        graphql = GraphQL.newGraphQL(schema).build();
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) postgres.stop();
    }

    @Test
    void factory_threadsUserIdThroughToServiceMethod() {
        ExecutionInput input = Graphitron.newExecutionInput(dsl, "alice")
            .query("{ greetingByUser }")
            .build();
        var result = graphql.execute(input);
        assertThat(result.getErrors()).isEmpty();
        assertThat(result.<Map<String, Object>>getData())
            .extractingByKey("greetingByUser").isEqualTo("hello alice");
    }

    @Test
    void missingContextValue_singletonThrowsIllegalStateExceptionWithFactoryHint() {
        // Direct unit-level assertion on the singleton's default impl. End-to-end execution
        // routes the IllegalStateException through the framework's redact path, which replaces
        // the original message with a correlation-id reference (server-log surface only). The
        // diagnostic the consumer reads at the typed Java boundary is the message the singleton
        // throws here.
        DataFetchingEnvironment env = DataFetchingEnvironmentImpl.newDataFetchingEnvironment()
            .graphQLContext(GraphQLContext.newContext().build())
            .build();
        IllegalStateException thrown = org.assertj.core.api.Assertions.catchThrowableOfType(
            IllegalStateException.class,
            () -> GraphitronContext.GraphitronContextImpl.INSTANCE
                .getContextArgument(env, "userId"));
        assertThat(thrown).isNotNull();
        assertThat(thrown.getMessage())
            .as("missing-value diagnostic names the contextArgument and the factory")
            .contains("userId")
            .contains("Graphitron.newExecutionInput(...)");
    }

    @Test
    void handRolledExecutionInput_missingContextValue_fetcherFailsRedactedThroughFramework() {
        // Hand-roll the input outside the factory: DSLContext under its typed key + singleton
        // GraphitronContextImpl under GraphitronContext.class, but no userId entry. The
        // generated fetcher's getContextArgument call reads value=null and throws; the framework
        // redacts the message into a correlation-id reference. End-to-end, the assertion is
        // "the fetch failed", "no value came back", and "the redact path engaged."
        ExecutionInput input = ExecutionInput.newExecutionInput()
            .query("{ greetingByUser }")
            .graphQLContext(b -> {
                b.put(DSLContext.class, dsl);
                b.put(GraphitronContext.class, GraphitronContext.GraphitronContextImpl.INSTANCE);
            })
            .dataLoaderRegistry(new org.dataloader.DataLoaderRegistry())
            .build();

        var result = graphql.execute(input);
        assertThat(result.getErrors()).isNotEmpty();
        assertThat(result.getErrors().toString())
            .as("framework redact path emits a correlation-id error")
            .contains("Reference:");
        assertThat(result.<Map<String, Object>>getData())
            .extractingByKey("greetingByUser")
            .isNull();
    }
}
