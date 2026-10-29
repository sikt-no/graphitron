package no.sikt.graphitron.rewrite.test.internal;

import no.sikt.graphitron.rewrite.test.SakilaDatabase;
import no.sikt.graphitron.rewrite.test.tier.ExecutionTier;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@ExecutionTier
class SakilaDatabaseTest {

    @Test
    void theSharedDatabaseHoldsInitSqlsSeededFilms() {
        try (var dsl = SakilaDatabase.dsl()) {
            assertThat(dsl.fetchOne("select count(*) from film where film_id between 1 and 5").get(0, Integer.class))
                .isEqualTo(5);
        }
    }

    @Test
    void aTenantDatabaseIsNamedForTheRunAndSwapsOnlyTheDatabaseInTheUrl() {
        var name = SakilaDatabase.tenantName("probe");
        assertThat(name).startsWith("probe_").hasSizeGreaterThan("probe_".length());
        // The database is the URL's path; a Testcontainers URL carries a query string after it.
        var url = SakilaDatabase.tenantUrl("probe");
        assertThat(url).startsWith("jdbc:");
        assertThat(java.net.URI.create(url.substring("jdbc:".length())).getPath()).isEqualTo("/" + name);
    }

    @Test
    void aTenantDatabaseIsEmptyAndSeparateFromTheSharedOneUntilDropped() {
        String stem = "harness_probe";
        SakilaDatabase.createTenantDatabase(stem);
        try (var tenant = DSL.using(SakilaDatabase.tenantUrl(stem), SakilaDatabase.user(), SakilaDatabase.password())) {
            assertThat(tenant.fetchOne("select current_database()").get(0, String.class))
                .isEqualTo(SakilaDatabase.tenantName(stem));
            assertThat(tenant.fetchOne(
                "select count(*) from information_schema.tables where table_name = 'film'").get(0, Integer.class))
                .as("a tenant database does not inherit the shared database's tables").isZero();
        } finally {
            SakilaDatabase.dropTenantDatabase(stem);
        }
        try (var dsl = SakilaDatabase.dsl()) {
            assertThat(dsl.fetchOne("select count(*) from pg_database where datname = ?",
                SakilaDatabase.tenantName(stem)).get(0, Integer.class)).isZero();
        }
    }
}
