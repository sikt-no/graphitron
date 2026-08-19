package no.sikt.graphitron.rewrite.test.querydb;

import graphql.schema.GraphQLScalarType;
import no.sikt.graphitron.generated.multischemamutation.Graphitron;
import no.sikt.graphitron.rewrite.test.jooq.udt.records.SessionClaimsRecord;
import no.sikt.graphitron.rewrite.test.tier.ExecutionTier;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.Map;

import static org.assertj.core.api.Assertions.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.INTEGER;
import static org.assertj.core.api.InstanceOfAssertFactories.LIST;
import static org.assertj.core.api.InstanceOfAssertFactories.MAP;

/**
 * The reported startup failure, end to end: a schema whose SDL names no built-in scalar but
 * {@code ID}, carrying one {@code @asConnection} field, must assemble and serve its pagination
 * surface.
 *
 * <p>Registration of a scalar on a programmatic schema is explicit ({@code schemaBuilder
 * .additionalType(Scalars.GraphQLInt)}), and the generator sources that set from what the
 * classification walk reached through authored SDL. Connection synthesis mints its surface after
 * that walk, so {@code Int} arrived in the emitted schema as a bare type reference on
 * {@code Connection.totalCount} and the minted {@code first} argument with nothing registering it,
 * and {@code GraphitronSchema.build()} threw {@code AssertException: type Int not found in schema}
 * at consumer startup. Generation itself never complained, which is why the proof has to run the
 * generated assembler rather than inspect it.
 *
 * <p>The fixture is {@code multischema-mutation.graphqls}, generated into
 * {@code no.sikt.graphitron.generated.multischemamutation}: the module's only schema whose text is
 * free of {@code Int}. The query round-trip goes through the escape-hatch engine
 * ({@code Graphitron.newGraphQL()} plus {@code newExecutionInput}), which attaches no
 * connection-lifecycle instrumentation, so this test owns no session identity and needs none; the
 * managed path over the same package is {@link SessionHookExecutionTest}'s subject.
 */
@ExecutionTier
class BuiltInScalarRegistrationExecutionTest {

    static PostgreSQLContainer postgres;
    static DSLContext dsl;

    @BeforeAll
    static void startDatabase() {
        var localUrl = System.getProperty("test.db.url");
        if (localUrl != null) {
            dsl = DSL.using(localUrl,
                System.getProperty("test.db.username", "postgres"),
                System.getProperty("test.db.password", "postgres"));
        } else {
            postgres = new PostgreSQLContainer("postgres:18-alpine").withInitScript("init.sql");
            postgres.start();
            dsl = DSL.using(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        }
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) postgres.stop();
    }

    @Test
    void connectionOnlyBuiltInScalars_assembleAndServeThePaginationSurface() {
        var graphql = Graphitron.newGraphQL().build();

        // Assembly is the failure the issue reported; getting an engine at all already proves it.
        var assembled = graphql.getGraphQLSchema();
        assertThat(assembled.getType("Int"))
            .as("Int reaches the schema only through the synthesised pagination surface, and must "
                + "be registered on that basis alone")
            .isInstanceOf(GraphQLScalarType.class);
        assertThat(assembled.getType("Float"))
            .as("registration follows what the schema references, so an unreferenced built-in "
                + "stays out")
            .isNull();

        // Serving proof: every scalar the minted surface names coerces on the way out: Int on
        // totalCount, String on cursor, Boolean on hasNextPage.
        var input = Graphitron.newExecutionInput(dsl, new SessionClaimsRecord("test-user", null))
            .query("""
                { eventsA { totalCount edges { cursor } pageInfo { hasNextPage } } }
                """)
            .build();
        var result = graphql.execute(input);
        assertThat(result.getErrors()).isEmpty();

        Map<String, Object> data = result.getData();
        assertThat(data).extractingByKey("eventsA", as(MAP))
            .satisfies(connection -> {
                assertThat(connection).extractingByKey("totalCount", as(INTEGER)).isEqualTo(1);
                assertThat(connection).extractingByKey("pageInfo", as(MAP))
                    .containsEntry("hasNextPage", false);
                assertThat(connection).extractingByKey("edges", as(LIST))
                    .hasSize(1)
                    .allSatisfy(edge -> assertThat(edge).asInstanceOf(MAP)
                        .extractingByKey("cursor").isInstanceOf(String.class));
            });
    }
}
