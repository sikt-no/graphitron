package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.model.jooq.JooqCatalog;
import no.sikt.graphitron.model.test.FactGraphs;
import no.sikt.graphitron.rewrite.model.TenantScopes;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.Set;
import java.util.TreeSet;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_JOOQ_PACKAGE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_TENANT_COLUMN;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The tables the store scopes by tenant, {@code graphitron_tenant_column}, against the ones
 * {@link TenantScopeClassifier} scopes over the same catalog and configured column, while both
 * stand: they agree on the database-per-tenant fixtures' columns, and part only where one table
 * carries two columns the configured name folds onto, which the store resolves to neither.
 */
@UnitTier
class TenantColumnAgreementTest {

    @RegisterExtension
    static final FactGraphs.Reader FACTS = FactGraphs.reader();

    private final JooqCatalog catalog = new JooqCatalog(DEFAULT_JOOQ_PACKAGE);

    private static Set<String> stored(String document) {
        var t = GRAPHITRON_TENANT_COLUMN;
        return FACTS.read(document, graph -> new TreeSet<>(graph.handle().dsl()
            .select(t.TABLE_SCHEMA.concat(".").concat(t.TABLE_NAME)).from(t)
            .where(t.GRAPH_NAME.eq(graph.handle().graphName()))
            .fetch(0, String.class)));
    }

    private Set<String> classified(String column) {
        return new TreeSet<>(((TenantScopes.Configured) TenantScopeClassifier.classify(catalog, column))
            .tenantScopedTables());
    }

    @Test
    void theFilmIdColumnScopesTheSameTables() {
        assertThat(stored("scenarios/tenant-column")).isNotEmpty().isEqualTo(classified("film_id"));
    }

    @Test
    void theConvertedOwnerOrgColumnScopesTheSameTables() {
        assertThat(stored("scenarios/tenant-column-converted")).isEqualTo(classified("owner_org"));
    }

    @Test
    void aTableCarryingTwoColumnsTheNameFoldsOntoIsScopedOnlyByTheClassifier() {
        var classified = classified("code");
        assertThat(classified).contains("nodeidfixture.case_twins");
        classified.remove("nodeidfixture.case_twins");
        assertThat(stored("scenarios/tenant-column-case-twins")).isEqualTo(classified);
    }
}
