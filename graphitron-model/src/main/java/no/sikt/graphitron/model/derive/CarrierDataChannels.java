package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;

import java.time.LocalDateTime;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_CARRIER_DATA_CHANNEL;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_CARRIER_DATA_CHANNEL_RULE;
import static org.jooq.impl.DSL.excluded;
import static org.jooq.impl.DSL.val;

/**
 * The capture-cadence writer of {@code graphitron_carrier_data_channel}: for each {@code @service}
 * field whose payload has exactly one object data field bound to no table, that field and whether
 * the service's object passes through it, filled from the rule view beside it.
 *
 * <p>Reads the code references and the classes' read slots, never a type's backing, so it runs
 * before {@link TypeBackings}. Marked and swept: upserted on the reading's instant, then the rows
 * this reading did not find are deleted.
 */
public final class CarrierDataChannels {

    private CarrierDataChannels() {}

    /** Re-derives the graph's carrier data channels and sweeps what no longer has one. */
    public static void derive(DSLContext dsl, String graphName, LocalDateTime touchedAt) {
        var c = GRAPHITRON_CARRIER_DATA_CHANNEL;
        var r = GRAPHITRON_CARRIER_DATA_CHANNEL_RULE;
        dsl.insertInto(c)
            .columns(c.GRAPH_NAME, c.TYPE_NAME, c.FIELD_NAME, c.PAYLOAD_TYPE_NAME, c.DATA_FIELD_NAME,
                c.PASSES_THROUGH, c.TOUCHED_AT)
            .select(dsl
                .select(r.GRAPH_NAME, r.TYPE_NAME, r.FIELD_NAME, r.PAYLOAD_TYPE_NAME,
                    r.DATA_FIELD_NAME, r.PASSES_THROUGH, val(touchedAt, c.TOUCHED_AT))
                .from(r)
                .where(r.GRAPH_NAME.eq(graphName)))
            .onConflict(c.GRAPH_NAME, c.TYPE_NAME, c.FIELD_NAME)
            .doUpdate()
            .set(c.PAYLOAD_TYPE_NAME, excluded(c.PAYLOAD_TYPE_NAME))
            .set(c.DATA_FIELD_NAME, excluded(c.DATA_FIELD_NAME))
            .set(c.PASSES_THROUGH, excluded(c.PASSES_THROUGH))
            .set(c.TOUCHED_AT, excluded(c.TOUCHED_AT))
            .execute();
        dsl.deleteFrom(c)
            .where(c.GRAPH_NAME.eq(graphName))
            .and(c.TOUCHED_AT.ne(touchedAt))
            .execute();
    }
}
