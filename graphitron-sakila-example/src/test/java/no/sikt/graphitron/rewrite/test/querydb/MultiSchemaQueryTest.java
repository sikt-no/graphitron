package no.sikt.graphitron.rewrite.test.querydb;

import graphql.ExecutionInput;
import graphql.GraphQL;
import graphql.schema.DataFetchingEnvironment;
import no.sikt.graphitron.generated.multischema.Graphitron;
import no.sikt.graphitron.generated.multischema.schema.GraphitronContext;
import no.sikt.graphitron.rewrite.test.tier.ExecutionTier;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.LIST;
import static org.assertj.core.api.InstanceOfAssertFactories.MAP;
import static org.assertj.core.api.InstanceOfAssertFactories.list;

/**
 * R83 execution-tier slice: drives a GraphQL query against the {@code multischema}
 * generated GraphQL endpoint (the third graphitron-maven-plugin execution in
 * {@code graphitron-sakila-example}'s pom). Runs against the same {@code rewrite_test}
 * PostgreSQL database that {@link GraphQLQueryTest} hits, but loads its own
 * {@link Graphitron} class from the {@code no.sikt.graphitron.generated.multischema}
 * output package so the cross-schema FK traversal exercises its own slice without
 * coupling to the sakila-side wiring.
 *
 * <p>The seed data lives in {@code graphitron-sakila-db/src/main/resources/init.sql}:
 * one row in {@code multischema_a.widget}, two rows in {@code multischema_b.gadget}
 * pointing at it, plus one row in each {@code event} collision table.
 *
 * <p>The single test exercises the cross-schema FK that motivated R78: a query for
 * {@code gadgets { id widget { id } }} that drills from {@code multischema_b.gadget}
 * through the {@code gadget_widget_id_fkey} reference to {@code multischema_a.widget}.
 * If a regression rewires the emitted code to look up the FK constraint or table class
 * under the wrong schema, the generated {@code Gadget.$fields} method emits source
 * that does not compile (see the compile-tier execution {@code rewrite-generate-multischema}
 * for that path); if it compiles but selects the wrong rows, this execution-tier test
 * catches the behaviour at runtime.
 */
@ExecutionTier
class MultiSchemaQueryTest {

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
            postgres = new PostgreSQLContainer("postgres:18-alpine")
                .withInitScript("init.sql");
            postgres.start();
            dsl = DSL.using(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        }

        graphql = Graphitron.newGraphQL().build();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> execute(String query) {        var input = Graphitron.newExecutionInput(dsl).query(query).build();
        var result = graphql.execute(input);
        assertThat(result.getErrors()).isEmpty();
        return result.getData();
    }

    @Test
    @SuppressWarnings("unchecked")
    void gadgetsTraverseCrossSchemaFkToWidget() {
        // The FK route is gadget.widget_id -> widget.widget_id, with the constraint held on
        // multischema_b. Both gadgets in the fixture point at the same widget (id=1), so the
        // round-trip is "two gadgets, one widget per gadget, both with the same name".
        Map<String, Object> data = execute("""
            { gadgets { gadgetId note widget { widgetId name } } }
            """);

        assertThat(data).extractingByKey("gadgets", as(list(Map.class)))
            .hasSize(2)
            .allSatisfy(gadget -> {
                Map<String, Object> g = (Map<String, Object>) gadget;
                assertThat(g).extractingByKey("widget", as(MAP))
                    .containsEntry("widgetId", 1)
                    .containsEntry("name", "alpha-widget");
            })
            .extracting(g -> ((Map<String, Object>) g).get("gadgetId"))
            .containsExactlyInAnyOrder(100, 101);
    }

    @Test
    @SuppressWarnings("unchecked")
    void widgetsResolveAgainstSchemaASegment() {
        // Sanity check: the unqualified-and-unique resolution path also serves real rows.
        Map<String, Object> data = execute("{ widgets { widgetId name } }");

        assertThat(data).extractingByKey("widgets", as(LIST))
            .singleElement(as(MAP))
            .containsEntry("widgetId", 1)
            .containsEntry("name", "alpha-widget");
    }

    @Test
    @SuppressWarnings("unchecked")
    void eventsResolveAgainstQualifiedSchemaASegment() {
        // The Event SDL type uses @table(name: "multischema_a.event") to disambiguate from
        // the multischema_b.event collision; this returns the schema-A row, not the B row.
        Map<String, Object> data = execute("{ events { eventId name } }");

        assertThat(data).extractingByKey("events", as(LIST))
            .singleElement(as(MAP))
            .containsEntry("eventId", 10)
            .containsEntry("name", "launch-a");
    }

    @Test
    @SuppressWarnings("unchecked")
    void signalsRouteToDiscriminatedTypesUnderNamedSchema() {
        // R395 regression guard. Signal is a single-table discriminated interface over
        // multischema_a.signal, declared as @table(name: "signal"): unqualified and lowercase.
        // 'signal' is unique to multischema_a so it resolves there, yet jOOQ renders the FROM token
        // schema-qualified as "multischema_a"."signal", which the bare directive string does not match
        // (it omits the schema). The selection drives all three discriminator emit sites: the
        // __discriminator__ routing projection, the WHERE ... IN restriction, and the AlertSignal
        // cross-table LEFT JOIN gate (widgetName, signal -> widget via the in-schema FK). Before R395
        // each qualified off the directive string and Postgres rejected the query with
        // "missing FROM-clause entry", surfacing as a fetch error so the field resolved null. After
        // the fix the rows route to their concrete types and the cross-table column resolves. This
        // pins the schema-qualification dimension; the uppercase/case-mismatch dimension is pinned at
        // the unit tier (TypeFetcherGeneratorTest's INTERFACE_BASE fixture), not here.
        Map<String, Object> data = execute("""
            { signals { __typename signalId label ... on AlertSignal { widgetName } } }
            """);

        assertThat(data).extractingByKey("signals", as(list(Map.class)))
            .hasSize(3)
            .satisfiesExactlyInAnyOrder(
                s -> assertThat((Map<String, Object>) s)
                    .containsEntry("__typename", "AlertSignal")
                    .containsEntry("signalId", 1)
                    .containsEntry("label", "disk-full")
                    .containsEntry("widgetName", "alpha-widget"),
                s -> assertThat((Map<String, Object>) s)
                    .containsEntry("__typename", "NoticeSignal")
                    .containsEntry("signalId", 2)
                    .containsEntry("label", "login")
                    .doesNotContainKey("widgetName"),
                s -> assertThat((Map<String, Object>) s)
                    .containsEntry("__typename", "AlertSignal")
                    .containsEntry("signalId", 3)
                    .containsEntry("label", "cpu-high")
                    .containsEntry("widgetName", "alpha-widget"));
    }
}
