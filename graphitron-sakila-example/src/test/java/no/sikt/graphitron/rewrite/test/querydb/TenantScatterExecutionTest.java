package no.sikt.graphitron.rewrite.test.querydb;

import no.sikt.graphitron.generated.multitenant.schema.GraphitronRuntime;
import no.sikt.graphitron.generated.multitenant.schema.GraphitronTransactionProvider.CommitPolicy;
import no.sikt.graphitron.generated.multitenant.schema.TenantConnections;
import no.sikt.graphitron.generated.multitenant.schema.TenantConnections.Outcome;
import no.sikt.graphitron.rewrite.test.tier.ExecutionTier;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Execution-tier proof of the {@code TenantConnections.scatter} substrate against real PostgreSQL,
 * over the multi-tenant fixture package (compiled with {@code <tenantColumn>film_id</tenantColumn>}):
 * genuinely parallel execution under the cap, per-tenant connection identity intact under
 * concurrency, an empty-result tenant coming back {@code Success}-empty (distinct from
 * {@code Failed}), and {@code releaseAll} after a concurrent scatter leaking nothing (asserted via
 * open/close connection counts). The unit-tier sibling ({@code TenantScatterSubstrateTest} in the
 * generator module) pins the cap, deadline, ordering, re-entrancy, and straggler contracts over
 * fake JDBC; this class proves the same helper drives real connections.
 */
@ExecutionTier
class TenantScatterExecutionTest {

    static PostgreSQLContainer postgres;
    static DSLContext dsl;                 // default database, out-of-band setup/cleanup
    static String jdbcUrl, jdbcUser, jdbcPassword;

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

        // Real database-per-tenant. tenant_2's film table stays deliberately empty: an empty
        // result is a Success carrying an empty value, distinct from Failed.
        for (String db : List.of("scatter_t1", "scatter_t2")) {
            dsl.execute("drop database if exists " + db + " with (force)");
            dsl.execute("create database " + db);
            try (var tenant = DSL.using(tenantUrl(db), jdbcUser, jdbcPassword)) {
                tenant.execute("create table film (film_id int primary key, title text not null)");
            }
        }
        try (var t1 = DSL.using(tenantUrl("scatter_t1"), jdbcUser, jdbcPassword)) {
            t1.execute("insert into film values (1, 'Tenant One Film')");
        }
    }

    @AfterAll
    static void stopDatabase() {
        if (dsl != null) {
            dsl.execute("drop database if exists scatter_t1 with (force)");
            dsl.execute("drop database if exists scatter_t2 with (force)");
        }
        if (postgres != null) postgres.stop();
    }

    @Test
    void scatter_runsTenantsGenuinelyInParallel_eachWorkerOnItsOwnTenantsConnection() {
        var runtime = new GraphitronRuntime(
            dataSource(null, null, null),
            Map.of(1, dataSource("scatter_t1", null, null), 2, dataSource("scatter_t2", null, null)),
            SQLDialect.POSTGRES, 2, Duration.ofSeconds(10));
        var tenants = new TenantConnections(runtime, "{}", CommitPolicy.COMMIT);
        try {
            long start = System.nanoTime();
            List<Outcome<String>> outcomes = tenants.scatter(List.of(1, 2), perTenant ->
                perTenant.fetchOne("select current_database() from pg_sleep(0.5)").get(0, String.class));
            long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

            // Two 500ms sleeps under a cap of 2: parallel execution finishes in well under the
            // 1000ms a serial run needs (evidence-backed requirement: the fan-out must overlap).
            assertThat(elapsedMillis)
                .as("the per-tenant sleeps overlap; serial execution would take >= 1000ms")
                .isLessThan(900);
            assertThat(outcomes)
                .as("each worker ran on its own tenant's connection, under concurrency")
                .allSatisfy(o -> assertThat(o).isInstanceOf(Outcome.Success.class));
            assertThat(((Outcome.Success<String>) outcomes.get(0)).value()).isEqualTo("scatter_t1");
            assertThat(((Outcome.Success<String>) outcomes.get(1)).value()).isEqualTo("scatter_t2");
        } finally {
            tenants.releaseAll();
        }
    }

    @Test
    void scatter_emptyResultTenantIsSuccessCarryingAnEmptyValue_distinctFromFailed() {
        var runtime = new GraphitronRuntime(
            dataSource(null, null, null),
            Map.of(1, dataSource("scatter_t1", null, null), 2, dataSource("scatter_t2", null, null)),
            SQLDialect.POSTGRES);
        var tenants = new TenantConnections(runtime, "{}", CommitPolicy.COMMIT);
        try {
            List<Outcome<List<String>>> outcomes = tenants.scatter(List.of(1, 2), perTenant ->
                perTenant.fetch("select title from film order by title").getValues("title", String.class));

            assertThat(outcomes.get(0)).isInstanceOf(Outcome.Success.class);
            assertThat(((Outcome.Success<List<String>>) outcomes.get(0)).value())
                .containsExactly("Tenant One Film");
            assertThat(outcomes.get(1))
                .as("RLS-style row scoping legitimately yields nothing: Success-empty, never Failed")
                .isInstanceOf(Outcome.Success.class);
            assertThat(((Outcome.Success<List<String>>) outcomes.get(1)).value()).isEmpty();
        } finally {
            tenants.releaseAll();
        }
    }

    @Test
    void releaseAll_afterAConcurrentScatter_leaksNoConnections() {
        AtomicInteger opened = new AtomicInteger();
        AtomicInteger closed = new AtomicInteger();
        var runtime = new GraphitronRuntime(
            dataSource(null, opened, closed),
            Map.of(1, dataSource("scatter_t1", opened, closed), 2, dataSource("scatter_t2", opened, closed)),
            SQLDialect.POSTGRES);
        var tenants = new TenantConnections(runtime, "{}", CommitPolicy.COMMIT);

        List<Outcome<Integer>> outcomes = tenants.scatter(List.of(1, 2), perTenant ->
            perTenant.fetchOne("select 1").get(0, Integer.class));
        assertThat(outcomes).allSatisfy(o -> assertThat(o).isInstanceOf(Outcome.Success.class));
        tenants.releaseAll();

        assertThat(opened.get()).as("one pin per distinct tenant, nothing for the default source").isEqualTo(2);
        assertThat(closed.get())
            .as("releaseAll after a concurrent scatter returns every pinned connection")
            .isEqualTo(opened.get());
    }

    // ===== helpers =====

    static String tenantUrl(String db) {
        return jdbcUrl.replaceAll("/[^/?]+(\\?|$)", "/" + db + "$1");
    }

    /**
     * A DataSource over {@code db} (or the default database when null), optionally counting
     * physical opens and closes so the leak assertion reads straight off the counters.
     */
    private static DataSource dataSource(String db, AtomicInteger opened, AtomicInteger closed) {
        String url = db == null ? jdbcUrl : tenantUrl(db);
        return (DataSource) Proxy.newProxyInstance(
            TenantScatterExecutionTest.class.getClassLoader(),
            new Class<?>[]{DataSource.class},
            (proxy, method, args) -> {
                if (method.getName().equals("getConnection")) {
                    if (opened != null) opened.incrementAndGet();
                    Connection real = DriverManager.getConnection(url, jdbcUser, jdbcPassword);
                    return closed == null ? real : countingClose(real, closed);
                }
                return switch (method.getName()) {
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "dataSource:" + (db == null ? "default" : db);
                    default -> throw new UnsupportedOperationException(method.getName());
                };
            });
    }

    private static Connection countingClose(Connection real, AtomicInteger closed) {
        return (Connection) Proxy.newProxyInstance(
            TenantScatterExecutionTest.class.getClassLoader(),
            new Class<?>[]{Connection.class},
            (proxy, method, args) -> {
                if (method.getName().equals("close") || method.getName().equals("abort")) {
                    if (!real.isClosed()) closed.incrementAndGet();
                }
                try {
                    return method.invoke(real, args);
                } catch (java.lang.reflect.InvocationTargetException e) {
                    throw e.getCause();
                }
            });
    }
}
