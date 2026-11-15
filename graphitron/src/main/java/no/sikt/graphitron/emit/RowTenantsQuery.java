package no.sikt.graphitron.emit;

import no.sikt.graphitron.model.read.StoreHandle;

import java.util.Map;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_TENANT_FAN_OUT;

/**
 * The fields routed by their parent row's tenant, by the schema coordinate the walk's tenant fold keys
 * its sites by, each with the column of the parent's table whose value names the tenant: the store is
 * the one decider of which fields fan out by a row's tenant, and the fold mints its binding off this.
 */
public final class RowTenantsQuery {

    private RowTenantsQuery() {}

    /** The graph's fields routed by their parent row's tenant, by coordinate, to the parent's column. */
    public static Map<String, String> read(StoreHandle store) {
        var t = GRAPHITRON_FIELD_TENANT_FAN_OUT;
        return Map.copyOf(store.dsl().select(t.ELEMENT_COORDINATE, t.PARENT_COLUMN)
            .from(t)
            .where(t.GRAPH_NAME.eq(store.graphName()))
            .fetchMap(t.ELEMENT_COORDINATE, t.PARENT_COLUMN));
    }
}
