package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;

import java.time.LocalDateTime;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_TENANT_COLUMN;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_TENANT_COLUMN_RULE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_TENANT_FAN_OUT_KEY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_TENANT_FAN_OUT_KEY_RULE;
import static org.jooq.impl.DSL.excluded;
import static org.jooq.impl.DSL.val;

/**
 * Writes {@code graphitron_tenant_column}: the catalog tables of the graph that carry the configured
 * tenant column, from the rule view beside it, marked and swept; and {@code graphitron_tenant_fan_out_key},
 * the foreign keys joining a table no tenant column scopes to a scoped table's tenant column, whose
 * parent row names the tenant.
 *
 * <p>Reads only the configuration and the catalog, both upstream of the graphitron gatherer, so it
 * runs first among the stages that resolve the graph against them.
 */
public final class TenantColumns {

    private TenantColumns() {}

    /** States the graph's tenant-scoped tables, and sweeps what the reading no longer finds. */
    public static void derive(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        var t = GRAPHITRON_TENANT_COLUMN;
        var r = GRAPHITRON_TENANT_COLUMN_RULE;
        dsl.insertInto(t)
            .columns(t.GRAPH_NAME, t.SOURCE_NAME, t.TABLE_SCHEMA, t.TABLE_NAME, t.COLUMN_NAME, t.TOUCHED_AT)
            .select(dsl
                .select(r.GRAPH_NAME, r.SOURCE_NAME, r.TABLE_SCHEMA, r.TABLE_NAME, r.COLUMN_NAME,
                    val(touchedAt, t.TOUCHED_AT))
                .from(r)
                .where(r.GRAPH_NAME.eq(graph)))
            .onConflict(t.GRAPH_NAME, t.SOURCE_NAME, t.TABLE_SCHEMA, t.TABLE_NAME)
            .doUpdate()
            .set(t.COLUMN_NAME, excluded(t.COLUMN_NAME))
            .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
            .execute();
        dsl.deleteFrom(t)
            .where(t.GRAPH_NAME.eq(graph))
            .and(t.TOUCHED_AT.ne(touchedAt))
            .execute();
        var k = GRAPHITRON_TENANT_FAN_OUT_KEY;
        var kr = GRAPHITRON_TENANT_FAN_OUT_KEY_RULE;
        dsl.insertInto(k)
            .columns(k.GRAPH_NAME, k.CONSTRAINT_SOURCE_NAME, k.CONSTRAINT_SCHEMA, k.CONSTRAINT_TABLE,
                k.CONSTRAINT_NAME, k.PARENT_ON_KEY_TABLE, k.PARENT_COLUMN, k.TOUCHED_AT)
            .select(dsl
                .select(kr.GRAPH_NAME, kr.CONSTRAINT_SOURCE_NAME, kr.CONSTRAINT_SCHEMA, kr.CONSTRAINT_TABLE,
                    kr.CONSTRAINT_NAME, kr.PARENT_ON_KEY_TABLE, kr.PARENT_COLUMN, val(touchedAt, k.TOUCHED_AT))
                .from(kr)
                .where(kr.GRAPH_NAME.eq(graph)))
            .onConflict(k.GRAPH_NAME, k.CONSTRAINT_SOURCE_NAME, k.CONSTRAINT_SCHEMA, k.CONSTRAINT_TABLE,
                k.CONSTRAINT_NAME)
            .doUpdate()
            .set(k.PARENT_ON_KEY_TABLE, excluded(k.PARENT_ON_KEY_TABLE))
            .set(k.PARENT_COLUMN, excluded(k.PARENT_COLUMN))
            .set(k.TOUCHED_AT, excluded(k.TOUCHED_AT))
            .execute();
        dsl.deleteFrom(k)
            .where(k.GRAPH_NAME.eq(graph))
            .and(k.TOUCHED_AT.ne(touchedAt))
            .execute();
    }
}
