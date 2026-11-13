package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;

import java.time.LocalDateTime;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_TYPE_IDENTIFYING_KEY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_TYPE_IDENTIFYING_KEY_RULE;
import static org.jooq.impl.DSL.excluded;
import static org.jooq.impl.DSL.select;
import static org.jooq.impl.DSL.val;

/**
 * The {@code type-identifying-keys} stage: the ranked keys that identify a row of each table-bound
 * type, into {@code graphitron_type_identifying_key}, from its rule view over the whole graph, marked
 * and swept. After {@link NodeKeyColumns}, whose node keys are one of the keys, and the table-bound
 * types it ranks them for.
 */
public final class TypeIdentifyingKeys {

    private TypeIdentifyingKeys() {}

    /** Writes the stage's relation, marked with this reading, then sweeps what it did not mark. */
    public static void derive(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        var k = GRAPHITRON_TYPE_IDENTIFYING_KEY;
        var r = GRAPHITRON_TYPE_IDENTIFYING_KEY_RULE;
        dsl.insertInto(k)
            .columns(k.GRAPH_NAME, k.TYPE_NAME, k.KEY_ORDINAL, k.POSITION, k.TABLE_SOURCE_NAME, k.TABLE_SCHEMA,
                k.TABLE_NAME, k.COLUMN_NAME, k.BASIS, k.TOUCHED_AT)
            .select(select(r.GRAPH_NAME, r.TYPE_NAME, r.KEY_ORDINAL, r.POSITION, r.TABLE_SOURCE_NAME,
                    r.TABLE_SCHEMA, r.TABLE_NAME, r.COLUMN_NAME, r.BASIS, val(touchedAt, k.TOUCHED_AT))
                .from(r).where(r.GRAPH_NAME.eq(graph)))
            .onConflict(k.GRAPH_NAME, k.TYPE_NAME, k.KEY_ORDINAL, k.POSITION)
            .doUpdate()
            .set(k.TABLE_SOURCE_NAME, excluded(k.TABLE_SOURCE_NAME))
            .set(k.TABLE_SCHEMA, excluded(k.TABLE_SCHEMA))
            .set(k.TABLE_NAME, excluded(k.TABLE_NAME))
            .set(k.COLUMN_NAME, excluded(k.COLUMN_NAME))
            .set(k.BASIS, excluded(k.BASIS))
            .set(k.TOUCHED_AT, excluded(k.TOUCHED_AT))
            .execute();
        dsl.deleteFrom(k).where(k.GRAPH_NAME.eq(graph), k.TOUCHED_AT.ne(touchedAt)).execute();
    }
}
