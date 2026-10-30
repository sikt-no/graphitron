package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;

import java.time.LocalDateTime;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_PATH_ELEMENT_KEY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_PATH_ELEMENT_KEY_RULE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_PATH_ELEMENT_METHOD;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_PATH_ELEMENT_METHOD_RULE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_PATH_ELEMENT_PRIMARY_KEY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_PATH_ELEMENT_PRIMARY_KEY_RULE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_PATH_ELEMENT_ROUTINE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_PATH_ELEMENT_ROUTINE_RULE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_PATH_ELEMENT_TABLE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_PATH_ELEMENT_TABLE_RULE;
import static org.jooq.impl.DSL.excluded;
import static org.jooq.impl.DSL.val;

/**
 * The capture-cadence writer of what each path element names: {@code graphitron_path_element_key},
 * {@code _primary_key}, {@code _table}, {@code _method} and {@code _routine}, one row per element
 * and candidate, each filled from its rule view beside it.
 *
 * <p>Reads only the element entries, written before the first stage, and the catalog and classpath
 * families, written before this gatherer starts, so it runs at the bottom of the reference stratum.
 * Marked and swept: upserted on the reading's instant, then the rows this reading did not name are
 * deleted, so a relation keyed into these cascades only what is really gone.
 */
public final class PathElements {

    private PathElements() {}

    /** Re-derives the graph's five resolutions and sweeps what each no longer names. */
    public static void derive(DSLContext dsl, String graphName, LocalDateTime touchedAt) {
        keys(dsl, graphName, touchedAt);
        primaryKeys(dsl, graphName, touchedAt);
        tables(dsl, graphName, touchedAt);
        methods(dsl, graphName, touchedAt);
        routines(dsl, graphName, touchedAt);
    }

    private static void keys(DSLContext dsl, String graphName, LocalDateTime touchedAt) {
        var t = GRAPHITRON_PATH_ELEMENT_KEY;
        var r = GRAPHITRON_PATH_ELEMENT_KEY_RULE;
        dsl.insertInto(t)
            .columns(t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN,
                t.CONSTRAINT_SOURCE_NAME, t.CONSTRAINT_SCHEMA, t.CONSTRAINT_TABLE, t.CONSTRAINT_NAME,
                t.MATCHED_BY, t.TOUCHED_AT)
            .select(dsl
                .select(r.GRAPH_NAME, r.SOURCE_NAME, r.SOURCE_LINE, r.SOURCE_COLUMN,
                    r.CONSTRAINT_SOURCE_NAME, r.CONSTRAINT_SCHEMA, r.CONSTRAINT_TABLE,
                    r.CONSTRAINT_NAME, r.MATCHED_BY, val(touchedAt, t.TOUCHED_AT))
                .from(r)
                .where(r.GRAPH_NAME.eq(graphName)))
            .onDuplicateKeyUpdate()
            .set(t.MATCHED_BY, excluded(t.MATCHED_BY))
            .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
            .execute();
        dsl.deleteFrom(t)
            .where(t.GRAPH_NAME.eq(graphName))
            .and(t.TOUCHED_AT.ne(touchedAt))
            .execute();
    }

    private static void primaryKeys(DSLContext dsl, String graphName, LocalDateTime touchedAt) {
        var t = GRAPHITRON_PATH_ELEMENT_PRIMARY_KEY;
        var r = GRAPHITRON_PATH_ELEMENT_PRIMARY_KEY_RULE;
        dsl.insertInto(t)
            .columns(t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN,
                t.TABLE_SOURCE_NAME, t.TABLE_SCHEMA, t.TABLE_NAME, t.MATCHED_BY, t.TOUCHED_AT)
            .select(dsl
                .select(r.GRAPH_NAME, r.SOURCE_NAME, r.SOURCE_LINE, r.SOURCE_COLUMN,
                    r.TABLE_SOURCE_NAME, r.TABLE_SCHEMA, r.TABLE_NAME, r.MATCHED_BY,
                    val(touchedAt, t.TOUCHED_AT))
                .from(r)
                .where(r.GRAPH_NAME.eq(graphName)))
            .onDuplicateKeyUpdate()
            .set(t.MATCHED_BY, excluded(t.MATCHED_BY))
            .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
            .execute();
        dsl.deleteFrom(t)
            .where(t.GRAPH_NAME.eq(graphName))
            .and(t.TOUCHED_AT.ne(touchedAt))
            .execute();
    }

    private static void tables(DSLContext dsl, String graphName, LocalDateTime touchedAt) {
        var t = GRAPHITRON_PATH_ELEMENT_TABLE;
        var r = GRAPHITRON_PATH_ELEMENT_TABLE_RULE;
        dsl.insertInto(t)
            .columns(t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN,
                t.TABLE_SOURCE_NAME, t.TABLE_SCHEMA, t.TABLE_NAME, t.TOUCHED_AT)
            .select(dsl
                .select(r.GRAPH_NAME, r.SOURCE_NAME, r.SOURCE_LINE, r.SOURCE_COLUMN,
                    r.TABLE_SOURCE_NAME, r.TABLE_SCHEMA, r.TABLE_NAME, val(touchedAt, t.TOUCHED_AT))
                .from(r)
                .where(r.GRAPH_NAME.eq(graphName)))
            .onDuplicateKeyUpdate()
            .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
            .execute();
        dsl.deleteFrom(t)
            .where(t.GRAPH_NAME.eq(graphName))
            .and(t.TOUCHED_AT.ne(touchedAt))
            .execute();
    }

    private static void methods(DSLContext dsl, String graphName, LocalDateTime touchedAt) {
        var t = GRAPHITRON_PATH_ELEMENT_METHOD;
        var r = GRAPHITRON_PATH_ELEMENT_METHOD_RULE;
        dsl.insertInto(t)
            .columns(t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN,
                t.METHOD_SOURCE_NAME, t.METHOD_CLASS_NAME, t.METHOD_NAME, t.METHOD_DESCRIPTOR,
                t.TOUCHED_AT)
            .select(dsl
                .select(r.GRAPH_NAME, r.SOURCE_NAME, r.SOURCE_LINE, r.SOURCE_COLUMN,
                    r.METHOD_SOURCE_NAME, r.METHOD_CLASS_NAME, r.METHOD_NAME,
                    r.METHOD_DESCRIPTOR, val(touchedAt, t.TOUCHED_AT))
                .from(r)
                .where(r.GRAPH_NAME.eq(graphName)))
            .onDuplicateKeyUpdate()
            .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
            .execute();
        dsl.deleteFrom(t)
            .where(t.GRAPH_NAME.eq(graphName))
            .and(t.TOUCHED_AT.ne(touchedAt))
            .execute();
    }

    private static void routines(DSLContext dsl, String graphName, LocalDateTime touchedAt) {
        var t = GRAPHITRON_PATH_ELEMENT_ROUTINE;
        var r = GRAPHITRON_PATH_ELEMENT_ROUTINE_RULE;
        dsl.insertInto(t)
            .columns(t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN,
                t.ROUTINE_SOURCE_NAME, t.TABLE_SCHEMA, t.ROUTINE_NAME, t.TOUCHED_AT)
            .select(dsl
                .select(r.GRAPH_NAME, r.SOURCE_NAME, r.SOURCE_LINE, r.SOURCE_COLUMN,
                    r.ROUTINE_SOURCE_NAME, r.TABLE_SCHEMA, r.ROUTINE_NAME, val(touchedAt, t.TOUCHED_AT))
                .from(r)
                .where(r.GRAPH_NAME.eq(graphName)))
            .onDuplicateKeyUpdate()
            .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
            .execute();
        dsl.deleteFrom(t)
            .where(t.GRAPH_NAME.eq(graphName))
            .and(t.TOUCHED_AT.ne(touchedAt))
            .execute();
    }
}
