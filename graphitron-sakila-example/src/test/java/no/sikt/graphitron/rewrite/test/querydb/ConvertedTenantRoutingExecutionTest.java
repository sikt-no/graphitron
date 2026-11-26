package no.sikt.graphitron.rewrite.test.querydb;

import no.sikt.graphitron.rewrite.test.SakilaDatabase;
import graphql.ExecutionResult;
import graphql.GraphQL;
import no.sikt.graphitron.generated.convertedtenant.Graphitron;
import no.sikt.graphitron.generated.convertedtenant.schema.GraphitronRuntime;
import no.sikt.graphitron.rewrite.test.services.OrgTenantSessionIdentity;
import no.sikt.graphitron.rewrite.test.tier.ExecutionTier;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AutoClose;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Execution-tier proof that tenant keys are compared by the tenant column's database value, over
 * the converted-tenant fixture package (compiled with {@code <tenantColumn>owner_org</tenantColumn>};
 * see {@code converted-tenant.graphqls}). {@code owner_org} is a {@code bigint} domain a jOOQ
 * converter maps to {@code String}, so the SQL binds {@code "0186"} as {@code 186}; the router
 * must agree, whichever spelling the client or the consumer supplied. Two databases
 * ({@code org_tenant_186}, {@code org_tenant_1120}) each hold their own tenant's {@code org_site}
 * rows behind counting {@code DataSource}s, which the request's connection source keys by the
 * canonical spelling the runtime hands it.
 */
@ExecutionTier
@SuppressWarnings("unchecked")
class ConvertedTenantRoutingExecutionTest {

    @AutoClose
    static DSLContext dsl;
    static String jdbcUrl, jdbcUser, jdbcPassword;

    static final String DB_186 = "org_tenant_186";
    static final String DB_1120 = "org_tenant_1120";
    static final AtomicInteger OPENED_186 = new AtomicInteger();
    static final AtomicInteger OPENED_1120 = new AtomicInteger();
    static final AtomicInteger OPENED_DEFAULT = new AtomicInteger();
    static GraphQL graphql;
    static DataSource defaultPool;
    static final Map<String, DataSource> BY_TENANT = new LinkedHashMap<>();

    @BeforeAll
    static void startDatabase() {
        jdbcUrl = SakilaDatabase.url();
        jdbcUser = SakilaDatabase.user();
        jdbcPassword = SakilaDatabase.password();

        dsl = SakilaDatabase.dsl();

        for (String db : List.of(DB_186, DB_1120)) {
            SakilaDatabase.createTenantDatabase(db);
            try (var tenant = DSL.using(SakilaDatabase.tenantUrl(db), jdbcUser, jdbcPassword)) {
                tenant.execute("create domain org_code_domain as bigint");
                tenant.execute("create table org_site (site_id int primary key,"
                    + " owner_org org_code_domain not null, issuer_org org_code_domain,"
                    + " site_name varchar(50) not null)");
            }
        }
        try (var t = DSL.using(SakilaDatabase.tenantUrl(DB_186), jdbcUser, jdbcPassword)) {
            t.execute("insert into org_site values (1, 186, 186, 'Tromsø main'), (2, 186, null, 'Tromsø annex')");
        }
        try (var t = DSL.using(SakilaDatabase.tenantUrl(DB_1120), jdbcUser, jdbcPassword)) {
            t.execute("insert into org_site values (3, 1120, 1120, 'Trondheim main')");
        }

        BY_TENANT.put("186", countingDataSource(DB_186, OPENED_186));
        BY_TENANT.put("1120", countingDataSource(DB_1120, OPENED_1120));
        defaultPool = countingDataSource(null, OPENED_DEFAULT);
        graphql = new GraphitronRuntime(SQLDialect.POSTGRES).newGraphQL(Graphitron.buildSchema(b -> {})).build();
    }

    @AfterAll
    static void stopDatabase() {
        if (dsl != null) {
            SakilaDatabase.dropTenantDatabase(DB_186);
            SakilaDatabase.dropTenantDatabase(DB_1120);
        }
    }

    @BeforeEach
    void resetCounters() {
        OPENED_186.set(0);
        OPENED_1120.set(0);
        OPENED_DEFAULT.set(0);
    }

    private static ExecutionResult execute(GraphQL on, Map<String, DataSource> byTenant, String query,
                                           List<String> tenants, String sub) {
        var source = Sources.convertedTenant("{\"sub\":\"" + sub + "\"}", defaultPool, byTenant, tenants);
        return on.execute(Graphitron.newOwnedExecutionInput(source).query(query).build());
    }

    /** Executes under a source whose fan-out domain is tenant 186 alone. */
    private static ExecutionResult execute(String query) {
        return execute(graphql, BY_TENANT, query, List.of("186"), "converted-tenant");
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
        // Both hosted tenants are in the domain, so a fan-out left un-narrowed would ask both.
        var result = execute(graphql, BY_TENANT, "{ sitesNarrowable(ownerOrg: \"0186\") { siteName } }",
            List.of("186", "1120"), "converted-tenant");

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
    void theSourceReceivesTheTenantInTheColumnsForm() {
        // The consumer spells the fan-out domain's tenant "0186"; the key the source is asked for,
        // and so the pool it looks up and the tenant its mount receives, is the column's "186".
        var opened = new AtomicInteger();

        var result = execute(graphql, Map.of("186", countingDataSource(DB_186, opened)),
            "{ sitesNarrowable { siteName } }", List.of("0186"), "consumer-spelled");

        assertThat(siteNames(result, "sitesNarrowable")).containsExactlyInAnyOrder("Tromsø main", "Tromsø annex");
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

    /** A DataSource over one database counting acquisitions; {@code database} null is the main one. */
    private static DataSource countingDataSource(String database, AtomicInteger counter) {
        String url = database == null ? jdbcUrl : SakilaDatabase.tenantUrl(database);
        return (DataSource) Proxy.newProxyInstance(
            ConvertedTenantRoutingExecutionTest.class.getClassLoader(),
            new Class<?>[]{DataSource.class},
            (proxy, method, args) -> {
                if (method.getName().equals("getConnection")) {
                    counter.incrementAndGet();
                    return SakilaDatabase.connect(url, jdbcUser, jdbcPassword);
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
