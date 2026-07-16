package no.sikt.graphitron.rewrite.test.querydb;

import graphql.ExecutionResult;
import graphql.GraphQL;
import no.sikt.graphitron.generated.Graphitron;
import no.sikt.graphitron.generated.GraphitronDevExecutor;
import no.sikt.graphitron.rewrite.test.tier.ExecutionTier;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.tools.json.JSONValue;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Execution-tier coverage of the generated {@code GraphitronDevExecutor} against real
 * PostgreSQL: the dev tool's execution path is the emitted executor over a single caller-supplied
 * connection, and this proves it sees what the app sees.
 *
 * <p>The fidelity check runs the same query twice: once through the executor (the dev tool path:
 * one plain JDBC connection, {@code ROLLBACK_ONLY}) and once through the in-app owned-connection
 * engine ({@code Graphitron.runtime(dataSource, POSTGRES)}), and asserts the two
 * {@code toSpecification()} payloads serialize identically.
 *
 * <p>The rollback proof drives a real mutation through the executor: the write is observable in
 * the response (the mutation transaction ran) but leaves no trace in the database (the
 * {@code ROLLBACK_ONLY} commit policy settled it by rolling back).
 *
 * <p>The claims round-trip rides this module's real {@code <sessionState>}
 * {@code <variables>} sugar: a successful execution proves the configured claims payload mounts
 * through the generated connect hook (the runtime is fail-closed, so execution proceeds only if
 * the hook ran), a malformed payload surfaces the hook's own database error verbatim (the
 * hook-is-the-validator rule; the payload demonstrably reached Postgres's {@code jsonb} parser),
 * and a missing payload fails loudly at the executor's own gate with a pointer at the config knob.
 * The RLS half of the hook contract (mounted identity scopes reads) is pinned by
 * {@link SessionHookExecutionTest} on the same emitted hook.
 */
@ExecutionTier
class DevExecuteExecutionTest {

    static final String CLAIMS = "{\"sub\":\"test-user\"}";
    static final String USER_ID = "test-user";

    static PostgreSQLContainer postgres;
    static DSLContext dsl;               // out-of-band, for independent no-trace assertions
    static String jdbcUrl, jdbcUser, jdbcPassword;
    static GraphQL inAppEngine;

    @BeforeAll
    static void startDatabase() {
        var localUrl = System.getProperty("test.db.url");
        if (localUrl != null) {
            jdbcUrl = localUrl;
            jdbcUser = System.getProperty("test.db.username", "postgres");
            jdbcPassword = System.getProperty("test.db.password", "postgres");
        } else {
            postgres = new PostgreSQLContainer("postgres:18-alpine").withInitScript("init.sql");
            postgres.start();
            jdbcUrl = postgres.getJdbcUrl();
            jdbcUser = postgres.getUsername();
            jdbcPassword = postgres.getPassword();
        }
        dsl = DSL.using(jdbcUrl, jdbcUser, jdbcPassword);
        inAppEngine = Graphitron.runtime(simpleDataSource(), SQLDialect.POSTGRES)
            .newGraphQL(Graphitron.buildSchema(b -> {})).build();
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @Test
    void query_throughTheExecutor_matchesDirectInAppExecution() throws Exception {
        String query = "{ films { filmId castMembers { actorId actor { firstName } } } }";

        String devResult;
        try (Connection connection = DriverManager.getConnection(jdbcUrl, jdbcUser, jdbcPassword)) {
            devResult = GraphitronDevExecutor.execute(
                connection, "POSTGRES", query, null, CLAIMS, Map.of("userId", USER_ID));
        }

        ExecutionResult inApp = inAppEngine.execute(
            Graphitron.newOwnedExecutionInput(CLAIMS, USER_ID).query(query).build());
        assertThat(inApp.getErrors()).as("errors: " + inApp.getErrors()).isEmpty();

        // Same serializer on both sides (the executor uses JSONValue internally), so byte-equal
        // JSON is the fidelity statement: the dev tool sees exactly what the app sees.
        assertThat(devResult).isEqualTo(JSONValue.toJSONString(inApp.toSpecification()));
    }

    @Test
    void variables_bindThroughTheExecutor() throws Exception {
        String query = "query($ids: [ID]) { filmById(film_id: $ids) { filmId title } }";
        String result;
        try (Connection connection = DriverManager.getConnection(jdbcUrl, jdbcUser, jdbcPassword)) {
            result = GraphitronDevExecutor.execute(
                connection, "POSTGRES", query, Map.of("ids", java.util.List.of("1")),
                CLAIMS, Map.of("userId", USER_ID));
        }
        assertThat(result).contains("\"filmId\":1").doesNotContain("\"errors\"");
    }

    @Test
    void mutation_writeIsObservableInTheResponse_butLeavesNoTrace() throws Exception {
        String title = "R428-DEV-EXECUTE-" + UUID.randomUUID();
        String mutation = """
            mutation { createFilm(in: { title: "%s", languageId: 1 }) { filmId title } }
            """.formatted(title);

        String result;
        try (Connection connection = DriverManager.getConnection(jdbcUrl, jdbcUser, jdbcPassword)) {
            result = GraphitronDevExecutor.execute(
                connection, "POSTGRES", mutation, null, CLAIMS, Map.of("userId", USER_ID));
        }

        // The mutation ran for real (the generated fetcher returned the inserted row) ...
        assertThat(result).contains(title).doesNotContain("\"errors\"");
        // ... but ROLLBACK_ONLY settled its transaction by rolling back: an independent
        // connection sees nothing, so dev exploration never persists a write.
        assertThat(dsl.fetchCount(DSL.table("film"), DSL.field("title").eq(title)))
            .as("the write left no trace")
            .isEqualTo(0);
    }

    @Test
    void mutation_fieldIndependenceSurvivesTheDeferredRollbackTopology() throws Exception {
        // Under ROLLBACK_ONLY the operation transaction is deferred and each mutation field is a
        // savepoint: the failing field (FK violation) discards exactly its own writes and errors,
        // the succeeding field's payload read-back still observes its write inside the open
        // transaction, and release discards everything.
        String good = "R428-GOOD-" + UUID.randomUUID();
        String bad = "R428-BAD-" + UUID.randomUUID();
        String mutation = """
            mutation {
                good: createFilm(in: { title: "%s", languageId: 1 }) { filmId title }
                bad:  createFilm(in: { title: "%s", languageId: 999999 }) { filmId title }
            }
            """.formatted(good, bad);

        String result;
        try (Connection connection = DriverManager.getConnection(jdbcUrl, jdbcUser, jdbcPassword)) {
            result = GraphitronDevExecutor.execute(
                connection, "POSTGRES", mutation, null, CLAIMS, Map.of("userId", USER_ID));
        }

        assertThat(result)
            .as("the successful field's write is observable in the response")
            .contains(good)
            .as("the failing field surfaces a GraphQL error")
            .contains("\"errors\"");
        assertThat(dsl.fetchCount(DSL.table("film"), DSL.field("title").in(good, bad)))
            .as("neither field's write persists")
            .isEqualTo(0);
    }

    @Test
    void missingClaims_failLoudAtTheExecutorGate_namingTheConfigKnob() throws Exception {
        // This module configures <sessionState>, so the emitted executor requires a claims
        // payload rather than running under a different security posture than production.
        try (Connection connection = DriverManager.getConnection(jdbcUrl, jdbcUser, jdbcPassword)) {
            assertThatThrownBy(() -> GraphitronDevExecutor.execute(
                    connection, "POSTGRES", "{ films { filmId } }", null, null, Map.of("userId", USER_ID)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("<sessionState>")
                .hasMessageContaining("GRAPHITRON_DEV_CLAIMS");
        }
    }

    @Test
    void malformedClaims_surfaceTheHooksOwnError() throws Exception {
        // The hook is the validator: the <variables> sugar's connect hook casts the payload to
        // jsonb in the database, so a malformed payload produces Postgres's own parse error,
        // proof the claims string travelled untouched all the way to the real hook.
        try (Connection connection = DriverManager.getConnection(jdbcUrl, jdbcUser, jdbcPassword)) {
            assertThatThrownBy(() -> GraphitronDevExecutor.execute(
                    connection, "POSTGRES", "{ films { filmId } }", null, "not-json",
                    Map.of("userId", USER_ID)))
                .hasMessageContaining("json");
        }
    }

    /** A DataSource over plain DriverManager connections, for the in-app comparison engine. */
    private static DataSource simpleDataSource() {
        return (DataSource) Proxy.newProxyInstance(
            DevExecuteExecutionTest.class.getClassLoader(),
            new Class<?>[] {DataSource.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getConnection" -> DriverManager.getConnection(jdbcUrl, jdbcUser, jdbcPassword);
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                case "toString" -> "simpleDataSource";
                default -> throw new UnsupportedOperationException(method.getName());
            });
    }
}
