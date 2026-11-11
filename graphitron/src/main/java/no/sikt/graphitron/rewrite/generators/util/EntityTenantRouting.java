package no.sikt.graphitron.rewrite.generators.util;

import no.sikt.graphitron.emit.EntityKeyQuery;
import no.sikt.graphitron.model.jooq.ColumnRef;
import no.sikt.graphitron.rewrite.model.EntityResolution;
import no.sikt.graphitron.rewrite.model.KeyAlternative;
import no.sikt.graphitron.rewrite.model.TenantBinding;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Where each entity's representations read their tenant, per key alternative: the seam the entity
 * dispatch takes its tenant routing through. Keyed by the entity's type and the alternative's
 * {@link EntityKeyQuery.Identity}, never by its place in a list, so the order the store gives the
 * alternatives and the order this value was filled in cannot silently disagree; a source that reads
 * the tenant off a key column names the column rather than its position.
 *
 * <p>A single-tenant build passes {@link #none()}. A multi-tenant build fills it through
 * {@link #fromWalk}, from the walk's tenant fold over the classified entities; that source goes when
 * the tenant facts are stated in the store, and only this value's construction changes then.
 */
public final class EntityTenantRouting {

    /** Where one alternative's representations read their tenant. */
    public sealed interface Source {
        /** The tenant is the value the representation carries for this key column of the entity's table. */
        record KeyColumn(String columnName) implements Source {}

        /** The tenant is the request's entity default tenant, matched against {@code tenantColumn}. */
        record RequestEntityDefault(ColumnRef tenantColumn) implements Source {}
    }

    private static final EntityTenantRouting NONE = new EntityTenantRouting(Map.of());

    private final Map<String, Map<EntityKeyQuery.Identity, Source>> byType;

    private EntityTenantRouting(Map<String, Map<EntityKeyQuery.Identity, Source>> byType) {
        this.byType = Map.copyOf(byType);
    }

    /** No entity routes per tenant: a single-tenant build. */
    public static EntityTenantRouting none() {
        return NONE;
    }

    /**
     * The routing the walk's tenant fold classified, paired with the store's alternatives: each walk
     * alternative carrying a source is matched by content to the one store alternative it states, the
     * node id to the node id and a key over columns to the store's key over the same fields. A walk
     * alternative with no partner or with several, a routed entity the store does not resolve, and a
     * resolvable store alternative left without a source each throw, since then the walk and the store
     * disagree on what the entity is.
     */
    public static EntityTenantRouting fromWalk(Map<String, TenantBinding.EntityRepBound> walkRouting,
                                               Map<String, EntityResolution> walkEntities,
                                               List<EntityKeyQuery.Entity> storeEntities) {
        var stored = new HashMap<String, EntityKeyQuery.Entity>();
        storeEntities.forEach(e -> stored.put(e.typeName(), e));
        var out = new HashMap<String, Map<EntityKeyQuery.Identity, Source>>();
        walkRouting.forEach((typeName, bound) -> {
            var walk = walkEntities.get(typeName);
            var store = stored.get(typeName);
            if (walk == null || store == null) {
                throw new IllegalStateException("Entity '" + typeName + "' routes per tenant, but "
                    + (walk == null ? "the walk" : "the store") + " resolves no such entity");
            }
            var sources = new HashMap<EntityKeyQuery.Identity, Source>();
            for (var slot : bound.alternatives()) {
                var walkAlternative = walk.alternatives().get(slot.alternativeIndex());
                var partner = partnerOf(typeName, walkAlternative, store.alternatives());
                Source source = switch (slot.source()) {
                    case TenantBinding.EntityRepBound.TenantSource.KeyColumn key ->
                        new Source.KeyColumn(walkAlternative.columns().get(key.decodedPosition()).sqlName());
                    case TenantBinding.EntityRepBound.TenantSource.RequestEntityDefault entityDefault ->
                        new Source.RequestEntityDefault(entityDefault.tenantColumn());
                };
                sources.put(partner.identity(), source);
            }
            for (var alternative : store.alternatives()) {
                if (alternative.resolvable() && !sources.containsKey(alternative.identity())) {
                    throw new IllegalStateException("Entity '" + typeName + "' routes per tenant, but its"
                        + " alternative " + alternative.identity() + " has no tenant source");
                }
            }
            out.put(typeName, sources);
        });
        return new EntityTenantRouting(out);
    }

    private static EntityKeyQuery.Alternative partnerOf(String typeName, KeyAlternative walkAlternative,
                                                        List<EntityKeyQuery.Alternative> storeAlternatives) {
        var matches = storeAlternatives.stream()
            .filter(alternative -> switch (walkAlternative) {
                case KeyAlternative.NodeId ignored -> alternative instanceof EntityKeyQuery.Alternative.NodeId;
                case KeyAlternative.Direct direct -> alternative instanceof EntityKeyQuery.Alternative.Direct
                    && EntityKeys.requiredFields(alternative).equals(direct.requiredFields());
            })
            .toList();
        if (matches.size() != 1) {
            throw new IllegalStateException("Entity '" + typeName + "''s key alternative over "
                + walkAlternative.requiredFields() + " matches " + matches.size()
                + " of the store's alternatives, where it must match one");
        }
        return matches.getFirst();
    }

    /** The column a key column source names, as its place among {@code columns}. */
    static int positionOf(String typeName, Source.KeyColumn source, List<ColumnRef> columns) {
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).sqlName().equals(source.columnName())) {
                return i;
            }
        }
        throw new IllegalStateException("Entity '" + typeName + "' reads its tenant off column '"
            + source.columnName() + "', which its key alternative does not select by");
    }

    /** {@link Optional#empty()} where {@code typeName} does not route. */
    Optional<Map<EntityKeyQuery.Identity, Source>> boundOf(String typeName) {
        return Optional.ofNullable(byType.get(typeName));
    }
}
