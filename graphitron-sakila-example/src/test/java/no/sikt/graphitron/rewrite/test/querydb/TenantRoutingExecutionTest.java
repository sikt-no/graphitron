package no.sikt.graphitron.rewrite.test.querydb;

import no.sikt.graphitron.generated.schema.GraphitronRuntime;
import no.sikt.graphitron.generated.schema.GraphitronTransactionProvider.CommitPolicy;
import no.sikt.graphitron.generated.schema.TenantConnections;
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
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R429 slice 4 execution-tier coverage of the tenant-keyed acquisition seam against real PostgreSQL:
 * the runtime's {@code Map<TenantId, DataSource>} construction and the {@code TenantConnections} carrier
 * route each divined key to its own {@code DataSource}, pinning one connection per distinct key.
 *
 * <p>Database-per-tenant is simulated with two schemas ({@code tenant_a}, {@code tenant_b}), each with a
 * {@code widget} table seeded disjointly, behind two {@code DataSource}s whose connections set
 * {@code search_path} to their schema. This proves routing "given a caller-known tenant" (test-supplied
 * keys, no R45 divination), which is the half of the isolation proof the R429/R45 split lands here; R45
 * proves the keys are divined correctly and reshapes these proofs onto inferred bindings.
 */
@ExecutionTier
class TenantRoutingExecutionTest {

    static PostgreSQLContainer postgres;
    static DSLContext dsl;                 // superuser, out-of-band setup/cleanup
    static String jdbcUrl, jdbcUser, jdbcPassword;

    static final Object KEY_A = "tenant-a";
    static final Object KEY_B = "tenant-b";

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

        dsl.execute("drop schema if exists tenant_a cascade");
        dsl.execute("drop schema if exists tenant_b cascade");
        dsl.execute("create schema tenant_a");
        dsl.execute("create schema tenant_b");
        dsl.execute("create table tenant_a.widget (id int primary key, name text not null)");
        dsl.execute("create table tenant_b.widget (id int primary key, name text not null)");
        dsl.execute("insert into tenant_a.widget values (1,'a1'),(2,'a2')");
        dsl.execute("insert into tenant_b.widget values (1,'b1')");
    }

    @AfterAll
    static void stopDatabase() {
        if (dsl != null) {
            dsl.execute("drop schema if exists tenant_a cascade");
            dsl.execute("drop schema if exists tenant_b cascade");
        }
        if (postgres != null) postgres.stop();
    }

    @Test
    void dslFor_routesEachKeyToItsOwnSource_seeingDisjointRows() {
        var runtime = new GraphitronRuntime(
            searchPathDataSource("public", null),
            Map.of(KEY_A, searchPathDataSource("tenant_a", null), KEY_B, searchPathDataSource("tenant_b", null)),
            SQLDialect.POSTGRES);
        var tenants = new TenantConnections(runtime, "{}", CommitPolicy.COMMIT);
        try {
            DSLContext a = tenants.dslFor(KEY_A);
            DSLContext b = tenants.dslFor(KEY_B);

            assertThat(a.fetch("select name from widget order by name").getValues("name", String.class))
                .as("tenant A's connection sees only tenant A's rows")
                .containsExactly("a1", "a2");
            assertThat(b.fetch("select name from widget order by name").getValues("name", String.class))
                .as("tenant B's connection sees only tenant B's rows")
                .containsExactly("b1");
        } catch (SQLException e) {
            throw new RuntimeException(e);
        } finally {
            tenants.releaseAll();
        }
    }

    @Test
    void dslFor_pinsOneConnectionPerDistinctKey_acrossOneOperation() {
        AtomicInteger opened = new AtomicInteger();
        var runtime = new GraphitronRuntime(
            searchPathDataSource("public", opened),
            Map.of(KEY_A, searchPathDataSource("tenant_a", opened), KEY_B, searchPathDataSource("tenant_b", opened)),
            SQLDialect.POSTGRES);
        var tenants = new TenantConnections(runtime, "{}", CommitPolicy.COMMIT);
        try {
            tenants.dslFor(KEY_A);
            tenants.dslFor(KEY_B);
            tenants.dslFor(KEY_A); // repeated key reuses its pinned connection

            assertThat(opened.get())
                .as("N distinct divined keys pin N connections; a repeated key pins none extra")
                .isEqualTo(2);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        } finally {
            tenants.releaseAll();
        }
    }

    // ===== helpers =====

    /** A DataSource whose connections set {@code search_path} to {@code schema}, optionally counting acquisitions. */
    private static DataSource searchPathDataSource(String schema, AtomicInteger counter) {
        return (DataSource) Proxy.newProxyInstance(
            TenantRoutingExecutionTest.class.getClassLoader(),
            new Class<?>[]{DataSource.class},
            (proxy, method, args) -> {
                if (method.getName().equals("getConnection")) {
                    if (counter != null) counter.incrementAndGet();
                    Connection c = DriverManager.getConnection(jdbcUrl, jdbcUser, jdbcPassword);
                    try (Statement st = c.createStatement()) {
                        st.execute("set search_path to " + schema);
                    }
                    return c;
                }
                return switch (method.getName()) {
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "searchPathDataSource:" + schema;
                    default -> throw new UnsupportedOperationException(method.getName());
                };
            });
    }
}
