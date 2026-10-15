package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.model.diagnostics.Rejection;
import no.sikt.graphitron.rewrite.model.TenantScopes;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_JOOQ_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import no.sikt.graphitron.model.jooq.JooqCatalog;

/**
 * Catalog-tier coverage for {@link TenantScopeClassifier}: the two-way tenant-scope table
 * classification against a configured tenant column, run over the real fixture jOOQ catalog.
 *
 * <p>Fixture columns exercised: {@code tenant_id} carries {@code Integer} on exactly
 * {@code catalogue}, {@code catalogue_item} and {@code catalogue_shelf} (the agreement shape); {@code active} carries
 * {@code Boolean} on {@code staff} but {@code Integer} on {@code customer} (the disagreement
 * shape); {@code issuer_org} binds {@code String} on both {@code org_site} (behind a converter
 * over {@code bigint}) and {@code plain_org_permit} (a plain {@code varchar}), the shape only the
 * database-type half of the agreement rejects; {@code owner_org} is converter-backed on
 * {@code org_site} alone.
 */
@UnitTier
class TenantScopeClassifierTest {

    private final JooqCatalog catalog = new JooqCatalog(DEFAULT_JOOQ_PACKAGE);

    @Test
    void noElementNoAxis() {
        assertThat(TenantScopeClassifier.classify(catalog, null))
            .isSameAs(TenantScopes.None.INSTANCE);
        assertThat(TenantScopes.None.INSTANCE.tenantScoped("public.paged_a")).isFalse();
    }

    @Test
    void classifiesCarryingTablesAsTenantScopedAndTheRestAsGlobal() {
        var scopes = TenantScopeClassifier.classify(catalog, "tenant_id");

        var configured = assertConfigured(scopes);
        assertThat(configured.columnName()).isEqualTo("tenant_id");
        assertThat(configured.conflicts()).isEmpty();
        assertThat(configured.tenantScopedTables())
            .containsExactly("public.catalogue", "public.catalogue_item", "public.catalogue_shelf");
        assertThat(configured.tenantScoped("public.catalogue")).isTrue();
        assertThat(configured.tenantScoped("public.film")).isFalse();
    }

    @Test
    void tenantTypeIsReadOffTheCatalogColumnType() {
        var configured = assertConfigured(TenantScopeClassifier.classify(catalog, "tenant_id"));

        assertThat(configured.tenantType()).isEqualTo(ClassName.get(Integer.class));
    }

    @Test
    void columnNameMatchingFollowsCatalogLookupSemantics() {
        // Java name first, then SQL name, both case-insensitive — the same semantics
        // JooqCatalog.findColumn applies to every directive-supplied column name.
        var viaJavaName = assertConfigured(TenantScopeClassifier.classify(catalog, "TENANT_ID"));

        assertThat(viaJavaName.tenantScopedTables())
            .containsExactly("public.catalogue", "public.catalogue_item", "public.catalogue_shelf");
    }

    @Test
    void mixedColumnTypesRejectTenantColumnTypeDisagreement() {
        var configured = assertConfigured(TenantScopeClassifier.classify(catalog, "active"));

        // Both carrying tables still classify tenant-scoped; the disagreement is a rejection,
        // not a silent demotion to global.
        assertThat(configured.tenantScopedTables())
            .contains("public.staff", "public.customer");
        assertThat(configured.conflicts()).hasSize(1);
        var disagreement = (Rejection.AuthorError.TenantColumnTypeDisagreement)
            configured.conflicts().get(0);
        assertThat(disagreement.columnName()).isEqualTo("active");
        assertThat(disagreement.tables())
            .extracting(Rejection.AuthorError.TenantColumnTypeDisagreement.TableSite::qualifiedTable)
            .contains("public.staff", "public.customer");
        assertThat(disagreement.tables())
            .extracting(Rejection.AuthorError.TenantColumnTypeDisagreement.TableSite::declared)
            .contains(Boolean.class.getName(), Integer.class.getName());
        assertThat(disagreement.message())
            .contains("tenant column 'active'")
            .contains("disagreeing Java types")
            .contains("public.staff")
            .contains("public.customer");
    }

    @Test
    void oneBoundTypeOverTwoDatabaseTypesRejectsTenantColumnTypeDisagreement() {
        // Both bind String, which a bound-type-only check would accept; org_site's sits behind a
        // converter over bigint, so "0186" would canonicalise to "186" there and stay "0186" here.
        var configured = assertConfigured(TenantScopeClassifier.classify(catalog, "issuer_org"));

        assertThat(configured.conflicts()).hasSize(1);
        var disagreement = (Rejection.AuthorError.TenantColumnTypeDisagreement)
            configured.conflicts().get(0);
        assertThat(disagreement.tables())
            .extracting(
                Rejection.AuthorError.TenantColumnTypeDisagreement.TableSite::qualifiedTable,
                Rejection.AuthorError.TenantColumnTypeDisagreement.TableSite::declared,
                Rejection.AuthorError.TenantColumnTypeDisagreement.TableSite::databaseType)
            .containsExactlyInAnyOrder(
                tuple("public.org_site", String.class.getName(), Long.class.getName()),
                tuple("public.plain_org_permit", String.class.getName(), String.class.getName()));
        assertThat(disagreement.message())
            .contains("public.org_site declared java.lang.String over database type java.lang.Long")
            .contains("public.plain_org_permit declared java.lang.String over database type java.lang.String");
    }

    @Test
    void theFirstCarryingTableIsTheRepresentativeColumn() {
        var converted = assertConfigured(TenantScopeClassifier.classify(catalog, "owner_org"));
        assertThat(converted.conflicts()).isEmpty();
        assertThat(converted.tenantType()).isEqualTo(ClassName.get(String.class));
        assertThat(converted.tenantTable().tableName()).isEqualTo("org_site");
        assertThat(converted.tenantColumn().javaName()).isEqualTo("OWNER_ORG");

        var plain = assertConfigured(TenantScopeClassifier.classify(catalog, "tenant_id"));
        assertThat(plain.tenantTable().tableName()).isEqualTo("catalogue");
        assertThat(plain.tenantColumn().javaName()).isEqualTo("TENANT_ID");
    }

    @Test
    void unknownColumnRejectsRatherThanSilentlyClassifyingEverythingGlobal() {
        var configured = assertConfigured(TenantScopeClassifier.classify(catalog, "no_such_column"));

        assertThat(configured.tenantScopedTables()).isEmpty();
        assertThat(configured.conflicts()).hasSize(1);
        var unknown = (Rejection.AuthorError.UnknownName) configured.conflicts().get(0);
        assertThat(unknown.attemptKind()).isEqualTo(Rejection.AttemptKind.COLUMN);
        assertThat(unknown.attempt()).isEqualTo("no_such_column");
        assertThat(unknown.message()).contains("matches no column on any catalog table");
    }

    private static TenantScopes.Configured assertConfigured(TenantScopes scopes) {
        assertThat(scopes).isInstanceOf(TenantScopes.Configured.class);
        return (TenantScopes.Configured) scopes;
    }
}
