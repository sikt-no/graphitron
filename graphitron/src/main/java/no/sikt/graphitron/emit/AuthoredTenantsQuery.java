package no.sikt.graphitron.emit;

import no.sikt.graphitron.model.read.StoreHandle;

import java.util.Set;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_BINDING_TENANT;

/**
 * The arguments and input fields a {@code $tenant} pair names a call's tenant by, by the schema
 * coordinate the walk's tenant fold keys its markers by: the store is the one reader of the sigil, and
 * the fold reads each as it reads {@code @tenant}. A pair ending past a node id is left out, the fold
 * finding a tenant in a node's key on its own.
 */
public final class AuthoredTenantsQuery {

    private AuthoredTenantsQuery() {}

    /** The graph's {@code $tenant} values, by the element coordinate of the occurrence each names. */
    public static Set<String> read(StoreHandle store) {
        var b = GRAPHITRON_OCCURRENCE_BINDING_TENANT;
        var o = GRAPHITRON_OCCURRENCE;
        return Set.copyOf(store.dsl().selectDistinct(o.ELEMENT_COORDINATE)
            .from(b)
            .join(o).on(o.GRAPH_NAME.eq(b.GRAPH_NAME), o.COORDINATE.eq(b.COORDINATE))
            .where(b.GRAPH_NAME.eq(store.graphName()), b.ORIGIN.eq("ARGMAPPING"), b.KEY_POSITION.isNull())
            .fetch(o.ELEMENT_COORDINATE));
    }
}
