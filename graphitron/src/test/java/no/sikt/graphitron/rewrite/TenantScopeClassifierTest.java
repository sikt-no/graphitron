package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.rewrite.model.Rejection;
import no.sikt.graphitron.rewrite.model.TenantScopes;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_JOOQ_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Catalog-tier coverage for {@link TenantScopeClassifier}: the two-way tenant-scope table
 * classification against a configured tenant column, run over the real fixture jOOQ catalog.
 *
 * <p>Fixture columns exercised: {@code k1} carries {@code Integer} on exactly
 * {@code paged_a} / {@code paged_b} (the agreement shape); {@code active} carries
 * {@code Boolean} on {@code staff} but {@code Integer} on {@code customer} (the disagreement
 * shape).
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
        var scopes = TenantScopeClassifier.classify(catalog, "k1");

        var configured = assertConfigured(scopes);
        assertThat(configured.columnName()).isEqualTo("k1");
        assertThat(configured.conflicts()).isEmpty();
        assertThat(configured.tenantScopedTables())
            .containsExactly("public.paged_a", "public.paged_b");
        assertThat(configured.tenantScoped("public.paged_a")).isTrue();
        assertThat(configured.tenantScoped("public.film")).isFalse();
    }

    @Test
    void tenantTypeIsReadOffTheCatalogColumnType() {
        var configured = assertConfigured(TenantScopeClassifier.classify(catalog, "k1"));

        assertThat(configured.tenantType()).isEqualTo(ClassName.get(Integer.class));
    }

    @Test
    void columnNameMatchingFollowsCatalogLookupSemantics() {
        // Java name first, then SQL name, both case-insensitive — the same semantics
        // JooqCatalog.findColumn applies to every directive-supplied column name.
        var viaJavaName = assertConfigured(TenantScopeClassifier.classify(catalog, "K1"));

        assertThat(viaJavaName.tenantScopedTables())
            .containsExactly("public.paged_a", "public.paged_b");
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
            .contains(ClassName.get(Boolean.class), ClassName.get(Integer.class));
        assertThat(disagreement.message())
            .contains("tenant column 'active'")
            .contains("disagreeing Java types")
            .contains("public.staff")
            .contains("public.customer");
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
