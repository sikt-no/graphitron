package no.sikt.graphitron.rewrite.test.querydb;

import graphql.ExecutionInput;
import graphql.GraphQL;
import graphql.schema.GraphQLSchema;
import no.sikt.graphitron.generated.Graphitron;
import no.sikt.graphitron.rewrite.test.tier.ExecutionTier;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R300 execution-tier proof for {@code @routine}: a real {@code RETURNS TABLE} function in the DB
 * backs a root list field, invoked end-to-end with its IN parameters bound from GraphQL arguments.
 * This is the proof that the generated {@code Routines.<method>(<bound args>)} call and the
 * FROM-attach actually run and return rows, and that selection narrowing projects only the columns
 * the query selected.
 */
@ExecutionTier
class RoutineFieldExecutionTest {

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
        if (postgres != null) {
            postgres.stop();
        }
    }

    @Test
    void tableValuedRoutineReturnsRowsWithArgumentsBound() {
        var data = execute("""
            { tilganger(env: "prod", serviceId: "svc", feideId: "feide-123") {
                organisasjonskode
                rollekode
            } }
            """);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get("tilganger");
        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(r -> r.get("organisasjonskode")).containsExactly(184, 185);
        assertThat(rows).extracting(r -> r.get("rollekode")).containsExactly("admin", "user");
    }

    @Test
    void selectionNarrowingProjectsOnlySelectedColumn() {
        // The function body executes in full, but the wrapping SELECT projects only the routine-result
        // column the query selected. The unselected `rollekode` must not appear in the response map.
        var data = execute("""
            { tilganger(env: "prod", serviceId: "svc", feideId: "feide-123") {
                organisasjonskode
            } }
            """);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get("tilganger");
        assertThat(rows).hasSize(2);
        assertThat(rows).allSatisfy(r -> {
            assertThat(r).containsKey("organisasjonskode");
            assertThat(r).doesNotContainKey("rollekode");
        });
        assertThat(rows).extracting(r -> r.get("organisasjonskode")).containsExactly(184, 185);
    }

    private Map<String, Object> execute(String query) {
        ExecutionInput input = Graphitron.newExecutionInput(dsl, "test-user").query(query).build();
        var result = graphql.execute(input);
        assertThat(result.getErrors()).isEmpty();
        return result.getData();
    }
}
