package no.sikt.graphitron.rewrite.test.querydb;

import graphql.GraphQL;
import no.sikt.graphitron.generated.Graphitron;
import no.sikt.graphitron.rewrite.test.tier.ExecutionTier;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The null contract of a nesting field against a real PostgreSQL database: a nullable field
 * returning a plain nesting type resolves to {@code null} when every column its type reads is
 * null, decided over all the type's fields whatever the query selects, and a non-null field
 * returning the same type always resolves to an object. Fixture: {@code PivotNestingHost.labels}
 * and {@code labelsRequired}, both {@code HostLabels} over {@code pivot_nesting_host.nn} and
 * {@code nb}; host 1 carries both columns, host 2 neither, host 3 only {@code nb}.
 */
@ExecutionTier
@SuppressWarnings("unchecked")
class NestingNullContractExecutionTest {

    static PostgreSQLContainer postgres;
    static DSLContext dsl;
    static GraphQL graphql;

    @BeforeAll
    static void startDatabase() {
        var localUrl = System.getProperty("test.db.url");
        if (localUrl != null) {
            var user = System.getProperty("test.db.username", "postgres");
            var pass = System.getProperty("test.db.password", "postgres");
            dsl = DSL.using(localUrl, user, pass);
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

    /** The hosts keyed by {@code hostId}, each carrying {@code selection}. */
    private Map<Integer, Map<String, Object>> hosts(String selection) {
        var input = Graphitron.newExecutionInput(dsl, "{}", "test-user")
            .query("{ pivotHosts { hostId " + selection + " } }").build();
        var result = graphql.execute(input);
        assertThat(result.getErrors()).isEmpty();
        var byId = new HashMap<Integer, Map<String, Object>>();
        for (var host : (List<Map<String, Object>>) ((Map<String, Object>) result.getData()).get("pivotHosts")) {
            byId.put((Integer) host.get("hostId"), host);
        }
        return byId;
    }

    @Test
    void nullableWrapper_withEveryColumnNull_resolvesToNull() {
        var host = hosts("labels { nn nb }").get(2);
        assertThat(host).containsEntry("labels", null);
    }

    @Test
    void nonNullWrapper_withEveryColumnNull_resolvesToAnObjectOfNulls() {
        var labels = (Map<String, Object>) hosts("labelsRequired { nn nb }").get(2).get("labelsRequired");
        assertThat(labels).containsEntry("nn", null).containsEntry("nb", null);
    }

    @Test
    void nullableWrapper_withColumnsPresent_resolvesToAnObject() {
        var labels = (Map<String, Object>) hosts("labels { nn nb }").get(1).get("labels");
        assertThat(labels).containsEntry("nn", "nn-vert").containsEntry("nb", "nb-vert");
    }

    @Test
    void presence_isDecidedOverEveryField_notTheSelection() {
        // Host 3 carries nb only; selecting just nn still resolves the wrapper to an object, so a
        // field's nullness never depends on its own sub-selection.
        var labels = (Map<String, Object>) hosts("labels { nn }").get(3).get("labels");
        assertThat(labels).isNotNull().containsEntry("nn", null);
        assertThat(hosts("labels { nn }").get(2)).containsEntry("labels", null);
    }
}
