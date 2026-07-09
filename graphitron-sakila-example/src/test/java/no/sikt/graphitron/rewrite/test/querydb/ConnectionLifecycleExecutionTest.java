package no.sikt.graphitron.rewrite.test.querydb;

import graphql.ExecutionInput;
import graphql.ExecutionResult;
import graphql.GraphQL;
import no.sikt.graphitron.generated.Graphitron;
import no.sikt.graphitron.generated.schema.GraphitronConnectionInstrumentation;
import no.sikt.graphitron.generated.schema.GraphitronContext;
import no.sikt.graphitron.generated.schema.GraphitronRuntime;
import no.sikt.graphitron.rewrite.test.tier.ExecutionTier;
import org.dataloader.DataLoaderRegistry;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.sql.DriverManager;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * R429 slice 2 execution-tier coverage of the owned-connection path against real PostgreSQL: the
 * connection-lifecycle instrumentation ({@code GraphitronRuntime.newGraphQL(schema)}) pins one
 * connection per operation, commits and rolls back mutation fields independently, and leaves
 * incremental delivery off. (Queries run in autocommit; blanket read-only enforcement was descoped to
 * R460, so there is no read-only transaction to assert here.)
 *
 * <p>These run the real generated engine over a {@code GraphitronRuntime} built on a
 * {@link DataSource} (not the escape-hatch {@code Graphitron.newExecutionInput(dsl, ...)} form): the
 * instrumentation acquires the connection and publishes the pinned {@code DSLContext}. Because slice
 * 5's ergonomic {@code runtime.newExecutionInput(claims, ...)} factory does not exist yet, the
 * per-request {@code graphQLContext} (claims, the sealed context singleton, the {@code userId}
 * contextArgument) is assembled directly here; slice 5 will replace {@link #ownedInput} with the
 * factory.
 */
@ExecutionTier
@SuppressWarnings("unchecked")
class ConnectionLifecycleExecutionTest {

    static PostgreSQLContainer postgres;
    static DSLContext dsl;               // out-of-band, for setup and assertions
    static String jdbcUrl, jdbcUser, jdbcPassword;

    /** Counts physical acquisitions so a multi-fetch operation can be shown to pin exactly one. */
    static final AtomicInteger CONNECTIONS_OPENED = new AtomicInteger();
    static DataSource countingDataSource;
    static GraphitronRuntime runtime;
    static GraphQL graphql;

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

        countingDataSource = countingDataSource();
        runtime = Graphitron.runtime(countingDataSource, SQLDialect.POSTGRES);
        graphql = runtime.newGraphQL(Graphitron.buildSchema(b -> {})).build();
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) postgres.stop();
    }

    // ===== one connection per operation =====

    @Test
    void query_multiFetchOperation_pinsExactlyOneConnection() {
        // films -> castMembers (batched) -> actor (batched): several SQL round trips, one operation.
        CONNECTIONS_OPENED.set(0);
        ExecutionResult result = graphql.execute(
            ownedInput("{ films { filmId castMembers { actorId actor { firstName } } } }").build());

        assertThat(result.getErrors()).as("errors: " + result.getErrors()).isEmpty();
        assertThat((Map<String, Object>) result.getData()).containsKey("films");
        assertThat(CONNECTIONS_OPENED.get())
            .as("a whole multi-fetch operation pins exactly one connection")
            .isEqualTo(1);
    }

    // ===== per-field mutation commit / rollback independence =====

    @Test
    void mutation_perField_commitsSuccessfulFieldAndRollsBackFailedField() {
        String good = "R429-COMMIT-" + UUID.randomUUID();
        String bad = "R429-ROLLBACK-" + UUID.randomUUID();
        try {
            // graphql-java runs top-level mutation fields serially: `good` commits its own writable
            // transaction; `bad` violates the language_id FK, so its transaction rolls back. The two
            // outcomes are independent, exactly GraphQL partial-success semantics.
            ExecutionResult result = graphql.execute(ownedInput("""
                mutation {
                    good: createFilm(in: { title: "%s", languageId: 1 }) { filmId title }
                    bad:  createFilm(in: { title: "%s", languageId: 999999 }) { filmId title }
                }
                """.formatted(good, bad)).build());

            assertThat(result.getErrors())
                .as("the FK-violating field produces an error")
                .isNotEmpty();
            assertThat(result.getErrors())
                .extracting(e -> e.getPath() == null ? "" : e.getPath().toString())
                .anyMatch(p -> p.contains("bad"));

            assertThat(dsl.fetchCount(DSL.table("film"), DSL.field("title").eq(good)))
                .as("the successful field's write is committed")
                .isEqualTo(1);
            assertThat(dsl.fetchCount(DSL.table("film"), DSL.field("title").eq(bad)))
                .as("the failed field's write is rolled back, independently of the committed one")
                .isEqualTo(0);
        } finally {
            dsl.deleteFrom(DSL.table("film")).where(DSL.field("title").eq(good)).execute();
        }
    }

    // ===== @defer stays off on the owned-connection path =====

    @Test
    void incrementalDelivery_isRejected_onOwnedConnectionPath() {
        // Connection-per-operation release would close the pinned connection out from under a
        // deferred fetcher, so the instrumentation refuses incremental delivery outright.
        ExecutionInput.Builder builder = ownedInput("{ films { filmId } }");
        GraphQL.unusualConfiguration(builder).incrementalSupport().enableIncrementalSupport(true);

        assertThatThrownBy(() -> graphql.execute(builder.build()))
            .as("owned-connection path rejects @defer/@stream")
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("Incremental delivery");
    }

    // ===== helpers =====

    /**
     * Assembles a per-request {@code ExecutionInput} for the owned-connection path: the opaque claims,
     * the sealed {@code GraphitronContext} singleton, and the {@code userId} contextArgument, plus a
     * fresh {@code DataLoaderRegistry}. The instrumentation publishes the pinned {@code DSLContext}
     * itself, so none is supplied here. Slice 5's {@code runtime.newExecutionInput(claims, ...)} factory
     * will subsume this.
     */
    private ExecutionInput.Builder ownedInput(String query) {
        return ExecutionInput.newExecutionInput()
            .query(query)
            .graphQLContext(b -> {
                b.put(GraphitronConnectionInstrumentation.CLAIMS_KEY, "test-claims");
                b.put(GraphitronContext.class, GraphitronContext.GraphitronContextImpl.INSTANCE);
                b.put("userId", "test-user");
            })
            .dataLoaderRegistry(new DataLoaderRegistry());
    }

    /** A DataSource that hands out real connections and counts each acquisition. */
    private static DataSource countingDataSource() {
        return (DataSource) Proxy.newProxyInstance(
            ConnectionLifecycleExecutionTest.class.getClassLoader(),
            new Class<?>[]{DataSource.class},
            (proxy, method, args) -> {
                if (method.getName().equals("getConnection")) {
                    CONNECTIONS_OPENED.incrementAndGet();
                    return DriverManager.getConnection(jdbcUrl, jdbcUser, jdbcPassword);
                }
                return switch (method.getName()) {
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "countingDataSource";
                    default -> throw new UnsupportedOperationException(method.getName());
                };
            });
    }
}
