package no.sikt.graphitron.rewrite.model;

import no.sikt.graphitron.javapoet.TypeName;

import java.util.List;
import java.util.Set;

/**
 * The catalog-wide tenant-scope classification: which tables live in the per-tenant databases
 * and which live on the default source, decided once at catalog load from the configured
 * {@code <tenantColumn>} element.
 *
 * <p>Sealed with an explicit {@link None} arm rather than {@code Optional<TenantScopes>}: the
 * principle "sealed hierarchies over presence-or-absence" prefers named arms, and further arms
 * (a declared tenant-index scope) can land later without touching every consumer.
 *
 * <p>Every field holds only resolved immutable values ({@link String}, {@link TypeName},
 * {@link Set}, {@link List}) so the classification can be read after the codegen loader closes,
 * the same invariant {@link no.sikt.graphitron.rewrite.catalog.CatalogFacts} documents.
 */
public sealed interface TenantScopes permits TenantScopes.None, TenantScopes.Configured {

    /**
     * True when the table with this schema-qualified SQL name ({@code "schema.table"}) carries
     * the tenant column and is partitioned per tenant; false for global tables and for every
     * table in a single-tenant build.
     */
    boolean tenantScoped(String qualifiedTableName);

    /**
     * Single-tenant: no {@code <tenantColumn>} element is configured, so no table carries a
     * tenant scope and no per-field tenant binding exists.
     */
    record None() implements TenantScopes {
        public static final None INSTANCE = new None();

        @Override public boolean tenantScoped(String qualifiedTableName) {
            return false;
        }
    }

    /**
     * A {@code <tenantColumn>} element is configured. Every catalog table classified into one
     * of two scopes: tenant-scoped (carries the column; a member of
     * {@link #tenantScopedTables}) or global (does not; absent from the set).
     *
     * @param columnName         the configured column name, as declared in the POM
     * @param tenantType         the tenant Java type read off the jOOQ catalog's column type.
     *                           When {@link #conflicts} is non-empty this is unreliable: the
     *                           first encountered type in schema-then-table order on a type
     *                           disagreement, or {@code null} when no table carries the column
     *                           at all. Both states carry a rejection that fails the build
     *                           before any consumer can emit against the placeholder.
     * @param tenantScopedTables schema-qualified SQL names ({@code "schema.table"}) of every
     *                           table carrying the column, in the catalog's stable
     *                           schema-then-table order
     * @param conflicts          the typed {@code tenantColumnTypeDisagreement} rejections; the
     *                           validator drains these into build errors
     */
    record Configured(
        String columnName,
        TypeName tenantType,
        Set<String> tenantScopedTables,
        List<Rejection> conflicts
    ) implements TenantScopes {
        public Configured {
            // LinkedHashSet-backed copy preserves the catalog's stable schema-then-table order.
            tenantScopedTables = java.util.Collections.unmodifiableSet(
                new java.util.LinkedHashSet<>(tenantScopedTables));
            conflicts = List.copyOf(conflicts);
        }

        @Override public boolean tenantScoped(String qualifiedTableName) {
            return tenantScopedTables.contains(qualifiedTableName);
        }
    }
}
