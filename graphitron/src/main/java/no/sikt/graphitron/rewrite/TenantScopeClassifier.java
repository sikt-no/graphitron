package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.rewrite.model.Rejection;
import no.sikt.graphitron.rewrite.model.TenantScopes;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.TreeSet;

/**
 * Classifies every catalog table into a tenant scope against the configured
 * {@code <tenantColumn>} element: tenant-scoped (carries the column; lives in the per-tenant
 * databases) or global (does not; lives on the default source).
 *
 * <p>Runs once at catalog load, while the codegen loader is open, and reduces every live jOOQ
 * handle to resolved immutable values, so the returned {@link TenantScopes} is readable after
 * the loader closes. The tenant Java type is not configured; it is read off the jOOQ catalog's
 * column type, and all tables carrying the column must agree on it. Disagreement is the typed
 * {@link Rejection.AuthorError.TenantColumnTypeDisagreement} rejection; a configured column no
 * catalog table carries is an {@link Rejection.AuthorError.UnknownName} rejection (a typo'd
 * declaration silently classifying every table as global would route tenant data through the
 * default connection, the exact leak the classification exists to prevent). Both drain through
 * the validator's tenant-binding mirror.
 *
 * <p>Absent the element ({@code tenantColumn == null}), returns {@link TenantScopes.None}: no
 * table carries a tenant scope and none of the downstream tenant machinery exists.
 */
public final class TenantScopeClassifier {

    private TenantScopeClassifier() {}

    public static TenantScopes classify(JooqCatalog catalog, String tenantColumn) {
        if (tenantColumn == null) {
            return TenantScopes.None.INSTANCE;
        }
        var scopedTables = new LinkedHashSet<String>();
        var sites = new ArrayList<Rejection.AuthorError.TenantColumnTypeDisagreement.TableSite>();
        var distinctTypes = new LinkedHashSet<TypeName>();
        var allColumnNames = new TreeSet<String>();
        for (var entry : catalog.allTableEntries()) {
            var table = entry.table();
            var column = catalog.findColumn(table, tenantColumn);
            if (column.isEmpty()) {
                for (var ref : entry.allColumnRefs()) {
                    allColumnNames.add(ref.sqlName());
                }
                continue;
            }
            String qualified = table.getSchema().getName() + "." + table.getName();
            scopedTables.add(qualified);
            sites.add(new Rejection.AuthorError.TenantColumnTypeDisagreement.TableSite(
                qualified, column.get().columnType()));
            distinctTypes.add(column.get().columnType());
        }
        var conflicts = new ArrayList<Rejection>();
        if (scopedTables.isEmpty()) {
            conflicts.add(new Rejection.AuthorError.UnknownName(
                "tenant column '" + tenantColumn + "' matches no column on any catalog table,"
                    + " so every table would silently classify as global",
                Rejection.AttemptKind.COLUMN,
                tenantColumn,
                List.copyOf(allColumnNames)));
        }
        if (distinctTypes.size() > 1) {
            conflicts.add(Rejection.tenantColumnTypeDisagreement(tenantColumn, sites));
        }
        // On disagreement the first encountered type (schema-then-table order) stands in as the
        // deterministic placeholder; the drained rejection fails the build before any consumer
        // emits against it.
        TypeName tenantType = distinctTypes.isEmpty() ? null : distinctTypes.iterator().next();
        return new TenantScopes.Configured(tenantColumn, tenantType, scopedTables, conflicts);
    }
}
