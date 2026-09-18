package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_SPELLED_REFERENCE_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_SPELLED_TABLE;
import static no.sikt.graphitron.model.Tables.SQL_TABLE;
import static no.sikt.graphitron.model.Tables.STORE_GRAPH_SOURCE;
import static org.jooq.impl.DSL.count;
import static org.jooq.impl.DSL.partitionBy;
import static org.jooq.impl.SQLDataType.INTEGER;

/**
 * The capture-cadence writer of {@code graphitron_spelled_table}: what every table name this graph
 * authors resolves to against the catalog census, once per distinct spelling.
 *
 * <p>The bottom rung of the reference stratum and the first stage of the graphitron gatherer to
 * write a resolution rather than a transcription. Everything it reads is captured by the time it
 * runs: the spellings are the walk's own entry relation, flushed before the first stage, and the
 * catalog side is the jooq gatherer's, written before this gatherer starts.
 *
 * <p>The rule does not vary by site, which is why one relation answers for all of them:
 * {@code @table(name:)}, a {@code @reference} path element's table, its argument-site and
 * {@code @referenceFor} siblings, {@code @mutation}'s delete target and {@code @routine(name:)}
 * all name a table the same way. A spelling arrives already split, capture having written the
 * namespace half and the name half beside the value, so both sides of both comparisons are stored
 * folded columns and the match is an equality an index can serve rather than a fold computed per
 * candidate row.
 *
 * <p>Ambiguity is rows and never a decline: a name two schemas both declare is two rows, and the
 * count beside each says how many, leaving the reading to the reader.
 */
public final class SpelledTables {

    private SpelledTables() {}

    /** Reconciles the graph's spelling partition: clears it, then re-derives it. */
    public static void derive(DSLContext dsl, String graphName) {
        var t = GRAPHITRON_SPELLED_TABLE;
        var s = GRAPHITRON_SPELLED_REFERENCE_ENTRY;
        var m = STORE_GRAPH_SOURCE;
        var st = SQL_TABLE;

        // Clear before insert, not upsert-and-sweep: the relation carries no reading instant and a
        // second capture of one graph is the ordinary case, so appending would double every row.
        dsl.deleteFrom(t).where(t.GRAPH_NAME.eq(graphName)).execute();
        dsl.insertInto(t)
            .columns(t.GRAPH_NAME, t.SPELLING, t.TABLE_SOURCE_NAME, t.TABLE_SCHEMA, t.TABLE_NAME,
                t.CANDIDATES)
            .select(dsl
                .select(s.GRAPH_NAME, s.SPELLING, st.SOURCE_NAME, st.TABLE_SCHEMA, st.TABLE_NAME,
                    count().over(partitionBy(s.GRAPH_NAME, s.SPELLING)).cast(INTEGER))
                .from(s)
                // The catalog side scopes through the graph's own sources, so a sibling graph's
                // tables never resolve here.
                .join(m).on(m.GRAPH_NAME.eq(s.GRAPH_NAME))
                .join(st).on(st.SOURCE_NAME.eq(m.SOURCE_NAME))
                    .and(st.TABLE_NAME_UPPER.eq(s.NAME_PART_UPPER))
                    // A qualified spelling binds both halves; an unqualified one, whose namespace
                    // half is null, matches on its name half alone.
                    .and(s.NAMESPACE_PART_UPPER.isNull()
                        .or(st.TABLE_SCHEMA_UPPER.eq(s.NAMESPACE_PART_UPPER)))
                .where(s.GRAPH_NAME.eq(graphName)))
            .execute();
    }
}
