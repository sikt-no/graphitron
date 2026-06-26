package no.sikt.graphitron.rewrite.test.querydb;

import graphql.GraphQL;
import graphql.schema.GraphQLSchema;
import no.sikt.graphitron.generated.Graphitron;
import no.sikt.graphitron.rewrite.test.tier.ExecutionTier;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R363 execution-tier proof: a {@code @field}-mapped filter input on a root multitable union query
 * is lowered per participant and ANDed into each UNION branch's {@code WHERE}, so the query returns
 * only matching rows.
 *
 * <p>{@code AddressOccupant = Customer | Staff}; both tables carry a {@code first_name} column. The
 * test covers <em>both</em> emit paths: {@code occupantsByName} (the non-connection list form,
 * {@code buildStage1Block}, which ANDs the filter into an existing per-branch {@code WHERE}) and
 * {@code occupantsByNameConnection} (the {@code @asConnection} form,
 * {@code buildStage1ConnectionBlock}, which previously emitted no per-branch {@code WHERE} and gains
 * one). Seed data: customers include {@code Mary}; staff include {@code Mike}; the two sets do not
 * overlap, so a two-value filter isolates exactly one row per participant.
 */
@ExecutionTier
class MultiTableFilterExecutionTest {

    static PostgreSQLContainer postgres;
    static DSLContext dsl;
    static GraphQL graphql;
    static final List<String> SQL_LOG = new java.util.concurrent.CopyOnWriteArrayList<>();

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
        dsl.configuration().set(new org.jooq.impl.DefaultExecuteListenerProvider(
            new org.jooq.ExecuteListener() {
                @Override
                public void executeStart(org.jooq.ExecuteContext ctx) {
                    var sql = ctx.sql();
                    if (sql != null) SQL_LOG.add(sql.toLowerCase(Locale.ROOT));
                }
            }));
        GraphQLSchema schema = Graphitron.buildSchema(b -> {});
        graphql = GraphQL.newGraphQL(schema).build();
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) postgres.stop();
    }

    @BeforeEach
    void clearSqlLog() {
        SQL_LOG.clear();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> execute(String query) {
        var input = Graphitron.newExecutionInput(dsl, "test-user").query(query).build();
        var result = graphql.execute(input);
        assertThat(result.getErrors()).isEmpty();
        return result.getData();
    }

    @Test
    @SuppressWarnings("unchecked")
    void listForm_filterMatchingOneParticipant_returnsOnlyThatRow() {
        Map<String, Object> data = execute("""
            { occupantsByName(firstName: ["Mary"]) {
                __typename
                ... on Customer { firstName }
                ... on Staff { firstName }
            } }
            """);
        List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get("occupantsByName");
        assertThat(rows)
            .as("only the Customer named Mary matches; no Staff is named Mary")
            .singleElement()
            .satisfies(r -> {
                assertThat(r.get("__typename")).isEqualTo("Customer");
                assertThat(r.get("firstName")).isEqualTo("Mary");
            });
    }

    @Test
    @SuppressWarnings("unchecked")
    void listForm_filterMatchingBothParticipants_appliesPerBranch() {
        // Mary is a customer, Mike is a staff member: the filter must narrow EACH branch by its own
        // first_name column, so exactly one row comes from each participant.
        Map<String, Object> data = execute("""
            { occupantsByName(firstName: ["Mary", "Mike"]) {
                __typename
                ... on Customer { firstName }
                ... on Staff { firstName }
            } }
            """);
        List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get("occupantsByName");
        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(r -> (String) r.get("firstName"))
            .containsExactlyInAnyOrder("Mary", "Mike");
        assertThat(rows).extracting(r -> (String) r.get("__typename"))
            .containsExactlyInAnyOrder("Customer", "Staff");
    }

    @Test
    @SuppressWarnings("unchecked")
    void listForm_filterMatchingNoRows_returnsEmpty() {
        Map<String, Object> data = execute("""
            { occupantsByName(firstName: ["NoSuchName"]) { __typename } }
            """);
        assertThat((List<Map<String, Object>>) data.get("occupantsByName")).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void nestedInputFilter_matchingBothParticipants_appliesPerBranch() {
        // R383: the same per-participant filter delivered through an input object (`filter`) rather
        // than as a top-level argument. The branch emitter reaches the value via a self-contained
        // Map traversal (env.getArgument("filter") instanceof Map ...), so each UNION branch still
        // narrows by its own first_name column.
        Map<String, Object> data = execute("""
            { occupantsByFilter(filter: { firstNames: ["Mary", "Mike"] }) {
                __typename
                ... on Customer { firstName }
                ... on Staff { firstName }
            } }
            """);
        List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get("occupantsByFilter");
        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(r -> (String) r.get("firstName"))
            .containsExactlyInAnyOrder("Mary", "Mike");
        assertThat(rows).extracting(r -> (String) r.get("__typename"))
            .containsExactlyInAnyOrder("Customer", "Staff");
        assertThat(SQL_LOG)
            .as("the nested-input filter still lowers to a per-branch first_name predicate")
            .anyMatch(s -> s.contains("first_name") && s.contains(" in ("));
    }

    @Test
    @SuppressWarnings("unchecked")
    void nestedInputFilter_omittedFilter_returnsAllRows() {
        // The filter input is nullable and the inner list is absent: the null-safe Map traversal
        // yields null, the condition method omits the predicate, and every occupant is returned.
        Map<String, Object> data = execute("""
            { occupantsByFilter(filter: {}) { __typename } }
            """);
        assertThat((List<Map<String, Object>>) data.get("occupantsByFilter"))
            .as("an empty filter narrows by nothing")
            .isNotEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void connectionForm_filterApplied_returnsOnlyMatchingNodes() {
        Map<String, Object> data = execute("""
            { occupantsByNameConnection(firstName: ["Mike"]) {
                nodes { __typename ... on Staff { firstName } }
                pageInfo { hasNextPage }
            } }
            """);
        var conn = (Map<String, Object>) data.get("occupantsByNameConnection");
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) conn.get("nodes");
        assertThat(nodes)
            .as("only Staff Mike matches; the connection branch WHERE must narrow per participant")
            .singleElement()
            .satisfies(n -> {
                assertThat(n.get("__typename")).isEqualTo("Staff");
                assertThat(n.get("firstName")).isEqualTo("Mike");
            });
        // The connection branch loop (buildStage1ConnectionBlock) must emit a per-branch first_name
        // predicate; before R363 it emitted no WHERE at all.
        assertThat(SQL_LOG)
            .as("the connection stage-1 SQL filters on first_name")
            .anyMatch(s -> s.contains("first_name") && s.contains(" in ("));
    }
}
