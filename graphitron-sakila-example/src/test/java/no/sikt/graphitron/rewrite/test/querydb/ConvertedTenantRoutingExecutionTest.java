package no.sikt.graphitron.rewrite.test.querydb;

import graphql.ExecutionResult;
import graphql.GraphQL;
import no.sikt.graphitron.generated.convertedtenant.Graphitron;
import no.sikt.graphitron.generated.convertedtenant.schema.GraphitronRuntime;
import no.sikt.graphitron.generated.convertedtenant.schema.RequestTenants;
import no.sikt.graphitron.rewrite.test.services.OrgTenantSessionIdentity;
import no.sikt.graphitron.rewrite.test.tier.ExecutionTier;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.sql.DriverManager;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Execution-tier proof that tenant keys are compared by the tenant column's database value, over
 * the converted-tenant fixture package (compiled with {@code <tenantColumn>owner_org</tenantColumn>};
 * see {@code converted-tenant.graphqls}). {@code owner_org} is a {@code bigint} domain a jOOQ
 * converter maps to {@code String}, so the SQL binds {@code "0186"} as {@code 186}; the router
 * must agree, whichever spelling the client or the consumer supplied. Two databases
 * ({@code org_tenant_186}, {@code org_tenant_1120}) each hold their own tenant's {@code org_site}
 * rows behind counting {@code DataSource}s keyed by the canonical spelling.
 */
@ExecutionTier
@SuppressWarnings("unchecked")
class ConvertedTenantRoutingExecutionTest {

    static PostgreSQLContainer postgres;
    static DSLContext dsl;
    static String jdbcUrl, jdbcUser, jdbcPassword;

    static final String DB_186 = "org_tenant_186";
    static final String DB_1120 = "org_tenant_1120";
    static final AtomicInteger OPENED_186 = new AtomicInteger();
    static final AtomicInteger OPENED_1120 = new AtomicInteger();
    static final AtomicInteger OPENED_DEFAULT = new AtomicInteger();
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

        for (String db : List.of(DB_186, DB_1120)) {
            dsl.execute("drop database if exists " + db + " with (force)");
            dsl.execute("create database " + db);
            try (var tenant = DSL.using(tenantUrl(db), jdbcUser, jdbcPassword)) {
                tenant.execute("create domain org_code_domain as bigint");
                tenant.execute("create table org_site (site_id int primary key,"
                    + " owner_org org_code_domain not null, issuer_org org_code_domain,"
                    + " site_name varchar(50) not null)");
            }
        }
        try (var t = DSL.using(tenantUrl(DB_186), jdbcUser, jdbcPassword)) {
            t.execute("insert into org_site values (1, 186, 186, 'Tromsø main'), (2, 186, null, 'Tromsø annex')");
        }
        try (var t = DSL.using(tenantUrl(DB_1120), jdbcUser, jdbcPassword)) {
            t.execute("insert into org_site values (3, 1120, 1120, 'Trondheim main')");
        }

        Map<String, DataSource> byTenant = new LinkedHashMap<>();
        byTenant.put("186", countingDataSource(DB_186, OPENED_186));
        byTenant.put("1120", countingDataSource(DB_1120, OPENED_1120));
        var runtime = new GraphitronRuntime(
            countingDataSource(null, OPENED_DEFAULT), byTenant, SQLDialect.POSTGRES);
        graphql = runtime.newGraphQL(Graphitron.buildSchema(b -> {})).build();
    }

    @AfterAll
    static void stopDatabase() {
        if (dsl != null) {
            dsl.execute("drop database if exists " + DB_186 + " with (force)");
            dsl.execute("drop database if exists " + DB_1120 + " with (force)");
        }
        if (postgres != null) postgres.stop();
    }

    @BeforeEach
    void resetCounters() {
        OPENED_186.set(0);
        OPENED_1120.set(0);
        OPENED_DEFAULT.set(0);
    }

    private static ExecutionResult execute(GraphQL on, String query, RequestTenants tenants, String sub) {
        return on.execute(Graphitron.newOwnedExecutionInput(tenants, "{\"sub\":\"" + sub + "\"}")
            .query(query).build());
    }

    /** Executes under a request tenant set admitting tenant 186 only. */
    private static ExecutionResult execute(String query) {
        return execute(graphql, query, RequestTenants.of(Set.of("186")), "converted-tenant");
    }

    private static List<Object> siteNames(ExecutionResult result, String field) {
        assertThat(result.getErrors()).as("errors: " + result.getErrors()).isEmpty();
        return ((List<Map<String, Object>>) ((Map<String, Object>) result.getData()).get(field))
            .stream().map(site -> site.get("siteName")).toList();
    }

    private int openedAnywhere() {
        return OPENED_186.get() + OPENED_1120.get() + OPENED_DEFAULT.get();
    }

    @Test
    void aClientSpellingTheColumnStoresAsTheTenantRoutesToIt() {
        var result = execute("{ sites(ownerOrg: \"0186\") { siteName } }");

        assertThat(siteNames(result, "sites")).containsExactlyInAnyOrder("Tromsø main", "Tromsø annex");
        assertThat(OPENED_186.get()).isPositive();
        assertThat(OPENED_1120.get()).isZero();
    }

    @Test
    void aNarrowedFanOutAsksTheTenantTheSpellingNames() {
        // Both hosted tenants are permitted, so a fan-out left un-narrowed would ask both.
        var result = execute(graphql, "{ sitesNarrowable(ownerOrg: \"0186\") { siteName } }",
            RequestTenants.of(Set.of("186", "1120")), "converted-tenant");

        assertThat(siteNames(result, "sitesNarrowable")).containsExactlyInAnyOrder("Tromsø main", "Tromsø annex");
        assertThat(OPENED_186.get()).isPositive();
        assertThat(OPENED_1120.get()).as("the narrowing names tenant 186 only").isZero();
    }

    @Test
    void twoSpellingsOfOneTenantAgree() {
        var result = execute("{ sitesIn(ownerOrg: [\"0186\", \"186\"]) { siteName } }");

        assertThat(siteNames(result, "sitesIn")).containsExactlyInAnyOrder("Tromsø main", "Tromsø annex");
        assertThat(OPENED_1120.get()).isZero();
    }

    @Test
    void theConsumersKeysTakeTheColumnsForm() {
        var opened = new AtomicInteger();
        var runtime = new GraphitronRuntime(countingDataSource(null, new AtomicInteger()),
            Map.of("0186", countingDataSource(DB_186, opened)), SQLDialect.POSTGRES);
        assertThat(runtime.tenantKeys()).containsExactly("186");
        var consumerSpelled = runtime.newGraphQL(Graphitron.buildSchema(b -> {})).build();
        var tenants = RequestTenants.of(Set.of("0186"));
        assertThat(tenants.tenants()).containsExactly("186");

        var result = execute(consumerSpelled, "{ sites(ownerOrg: \"186\") { siteName } }", tenants,
            "consumer-spelled");

        assertThat(siteNames(result, "sites")).containsExactlyInAnyOrder("Tromsø main", "Tromsø annex");
        assertThat(opened.get()).isPositive();
        assertThat(OrgTenantSessionIdentity.MOUNTS.stream()
                .filter(m -> "consumer-spelled".equals(m.sub()))
                .map(OrgTenantSessionIdentity.Mount::tenant))
            .as("the mount receives the tenant in the column's form")
            .containsOnly(Optional.of("186"));
    }

    @Test
    void aValueTheColumnCannotStoreIsAClientErrorBeforeAnyConnection() {
        var result = execute("{ sites(ownerOrg: \"abc\") { siteName } }");

        assertThat(result.getErrors().toString())
            .contains("Tenant value 'abc' is not a value of the tenant column");
        assertThat(openedAnywhere()).isZero();
    }

    @Test
    void aNumberOutsideTheColumnsRangeIsAClientErrorBeforeAnyConnection() {
        // The converter's Long.valueOf refuses it on the way to the database value; the column trip
        // reads that as "not a value of the column", whichever runtime class holds the trip.
        var result = execute("{ sites(ownerOrg: \"99999999999999999999\") { siteName } }");

        assertThat(result.getErrors().toString())
            .contains("Tenant value '99999999999999999999' is not a value of the tenant column");
        assertThat(openedAnywhere()).isZero();
    }

    @Test
    void aNarrowedFanOutOverAValueTheColumnCannotStoreIsTheSameClientError() {
        var result = execute("{ sitesNarrowable(ownerOrg: \"abc\") { siteName } }");

        assertThat(result.getErrors().toString())
            .contains("Tenant value 'abc' is not a value of the tenant column");
        assertThat(((Map<String, Object>) result.getData()).get("sitesNarrowable"))
            .as("an error, not an empty list presented as complete")
            .isNull();
        assertThat(openedAnywhere()).isZero();
    }

    @Test
    void twoConsumerKeysNamingOneTenantAreRefusedAtConstruction() {
        Map<String, DataSource> byTenant = new LinkedHashMap<>();
        byTenant.put("0186", countingDataSource(DB_186, new AtomicInteger()));
        byTenant.put("186", countingDataSource(DB_186, new AtomicInteger()));

        assertThatThrownBy(() -> new GraphitronRuntime(
                countingDataSource(null, new AtomicInteger()), byTenant, SQLDialect.POSTGRES))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("'0186'")
            .hasMessageContaining("'186'");
    }

    private static String tenantUrl(String database) {
        return jdbcUrl.replaceFirst("(jdbc:postgresql://[^/]+/)[^?]*", "$1" + database);
    }

    /** A DataSource over one database counting acquisitions; {@code database} null is the main one. */
    private static DataSource countingDataSource(String database, AtomicInteger counter) {
        String url = database == null ? jdbcUrl : tenantUrl(database);
        return (DataSource) Proxy.newProxyInstance(
            ConvertedTenantRoutingExecutionTest.class.getClassLoader(),
            new Class<?>[]{DataSource.class},
            (proxy, method, args) -> {
                if (method.getName().equals("getConnection")) {
                    counter.incrementAndGet();
                    return DriverManager.getConnection(url, jdbcUser, jdbcPassword);
                }
                return switch (method.getName()) {
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "orgTenantDataSource:" + (database == null ? "default" : database);
                    default -> throw new UnsupportedOperationException(method.getName());
                };
            });
    }
}
