package no.sikt.graphitron.rewrite.test.querydb;

import graphql.ExecutionInput;
import graphql.GraphQL;
import graphql.GraphQLContext;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.DataFetchingEnvironmentImpl;
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
 * L6 execution-tier tests for the schema-driven {@code Graphitron.newExecutionInput} factory and
 * the request-context reads it feeds:
 *
 * <ul>
 *   <li><b>Service round-trip</b>: the {@code @service(contextArguments: ["userId"])} site
 *       ({@code Query.greetingByUser}) is queried, threading {@code userId} through the typed
 *       factory parameter. The {@code UserGreetingService.greet(String)} method receives the
 *       value and renders it into the response.</li>
 *   <li><b>Condition round-trips</b>: the {@code @condition(contextArguments: ["userId"])}
 *       coordinates (root and batched child) read the same value through the env-appending
 *       condition glue signature and narrow their rows by it; the sibling same-named filter
 *       fixture pins the glue's producer-named locals end to end.</li>
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
        graphql = Graphitron.newGraphQL().build();
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
    @SuppressWarnings("unchecked")
    void conditionContextArgument_rootCoordinate_narrowsByTheThreadedUserId() {
        // The env-appending glue signature end to end: Query.customersSeenByUser carries
        // @condition(contextArguments: ["userId"]) with no field arguments at all, so the only
        // way MARY-rows can come back is the glue method reading userId off the request context
        // through its own graphitronContext helper.
        ExecutionInput input = Graphitron.newExecutionInput(dsl, "MARY")
            .query("{ customersSeenByUser { firstName } }")
            .build();
        var result = graphql.execute(input);
        assertThat(result.getErrors()).isEmpty();
        java.util.List<Map<String, Object>> rows = result.<Map<String, Object>>getData() == null
            ? java.util.List.of()
            : (java.util.List<Map<String, Object>>) result.<Map<String, Object>>getData().get("customersSeenByUser");
        assertThat(rows).isNotEmpty()
            .allSatisfy(r -> assertThat((String) r.get("firstName")).isEqualToIgnoringCase("MARY"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void conditionContextArgument_batchedChildCoordinate_narrowsByTheThreadedUserId() {
        // The fetcher-hosted twin: the same context-bound condition on Store.customersSeenByUser
        // (a @splitQuery child), threading userId into the batched rows method's glue call.
        ExecutionInput input = Graphitron.newExecutionInput(dsl, "MARY")
            .query("{ storeById(store_id: [1]) { customersSeenByUser { firstName } } }")
            .build();
        var result = graphql.execute(input);
        assertThat(result.getErrors()).isEmpty();
        java.util.List<Map<String, Object>> stores =
            (java.util.List<Map<String, Object>>) result.<Map<String, Object>>getData().get("storeById");
        assertThat(stores).hasSize(1);
        java.util.List<Map<String, Object>> rows =
            (java.util.List<Map<String, Object>>) stores.get(0).get("customersSeenByUser");
        assertThat(rows).isNotEmpty()
            .allSatisfy(r -> assertThat((String) r.get("firstName")).isEqualToIgnoringCase("MARY"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void siblingSameNamedFilterFields_bindAsQualifiedLocalsInOneGlueBody() {
        // Two sibling inputs each expose a field literally named `name` over different columns;
        // the glue body binds both as producer-named, outer-qualified locals, the shape the
        // retired entity layer's fixed parameter list could not compile. Both predicates must
        // fire: Mary + Smith narrows to the one customer carrying both (the generated
        // predicates are case-sensitive equalities, matching the seed data's exact case).
        ExecutionInput input = Graphitron.newExecutionInput(dsl, "test-user")
            .query("{ customersByTwoNames(a: { name: \"Mary\" }, b: { name: \"Smith\" }) { firstName lastName } }")
            .build();
        var result = graphql.execute(input);
        assertThat(result.getErrors()).isEmpty();
        java.util.List<Map<String, Object>> rows =
            (java.util.List<Map<String, Object>>) result.<Map<String, Object>>getData().get("customersByTwoNames");
        assertThat(rows).isNotEmpty()
            .allSatisfy(r -> {
                assertThat((String) r.get("firstName")).isEqualTo("Mary");
                assertThat((String) r.get("lastName")).isEqualTo("Smith");
            });
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
