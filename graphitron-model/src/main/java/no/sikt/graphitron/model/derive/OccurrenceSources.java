package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;

import java.time.LocalDateTime;

import static no.sikt.graphitron.model.Tables.CODE_GRAPH_CLASS;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_SOURCE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_SOURCE_OBJECT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_SOURCE_OBJECT_RULE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_SOURCE_RULE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_SOURCE_TABLE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_SOURCE_TABLE_RULE;
import static no.sikt.graphitron.model.Tables.SQL_TABLE;
import static org.jooq.impl.DSL.excluded;
import static org.jooq.impl.DSL.exists;
import static org.jooq.impl.DSL.selectDistinct;
import static org.jooq.impl.DSL.selectOne;
import static org.jooq.impl.DSL.val;

/**
 * The capture-cadence writer of {@code graphitron_occurrence_source} and its subtypes: what each
 * occurrence's values come from, rows of one or more tables or an instance of a class, marked and
 * swept.
 *
 * <p>A source is stated on the occurrence, never as a pointer to another. Each rule says what it
 * claims an occurrence reads, in {@code graphitron_occurrence_source_claim}
 * ({@link OccurrenceSourceClaims}); the source and its subtypes are written from the claims that take
 * effect, as the source rule views state, a depth at a time inside {@link OccurrenceDepths}: a field
 * under a parent reads what this reading stated for the parent at the depth above.
 *
 * <p>A kind is part of its subtypes' keys, so a source whose kind changed since the last reading is
 * deleted before it is written again, taking its subtypes.
 */
public final class OccurrenceSources {

    private OccurrenceSources() {}

    /** States the claims of the rules that read the parent, then the sources of the occurrences at {@code depth}. */
    public static void writeDepth(DSLContext dsl, String graph, int depth, LocalDateTime touchedAt) {
        OccurrenceSourceClaims.writeDepth(dsl, graph, depth, touchedAt);
        var o = GRAPHITRON_OCCURRENCE;

        // A source whose kind changed goes with its subtypes first.
        var s = GRAPHITRON_OCCURRENCE_SOURCE;
        var r = GRAPHITRON_OCCURRENCE_SOURCE_RULE;
        dsl.deleteFrom(s)
            .where(s.GRAPH_NAME.eq(graph))
            .and(exists(selectOne().from(r)
                .join(o).on(o.GRAPH_NAME.eq(r.GRAPH_NAME), o.COORDINATE.eq(r.COORDINATE))
                .where(r.GRAPH_NAME.eq(s.GRAPH_NAME), r.COORDINATE.eq(s.COORDINATE), r.KIND.ne(s.KIND),
                    o.DEPTH.eq(depth))))
            .execute();

        dsl.insertInto(s)
            .columns(s.GRAPH_NAME, s.COORDINATE, s.KIND, s.TOUCHED_AT)
            .select(selectDistinct(r.GRAPH_NAME, r.COORDINATE, r.KIND, val(touchedAt, s.TOUCHED_AT))
                .from(r)
                .join(o).on(o.GRAPH_NAME.eq(r.GRAPH_NAME), o.COORDINATE.eq(r.COORDINATE))
                .where(r.GRAPH_NAME.eq(graph), o.DEPTH.eq(depth)))
            .onConflict(s.GRAPH_NAME, s.COORDINATE)
            .doUpdate()
            .set(s.TOUCHED_AT, excluded(s.TOUCHED_AT))
            .execute();

        var t = GRAPHITRON_OCCURRENCE_SOURCE_TABLE;
        var tr = GRAPHITRON_OCCURRENCE_SOURCE_TABLE_RULE;
        var st = SQL_TABLE;
        dsl.insertInto(t)
            .columns(t.GRAPH_NAME, t.COORDINATE, t.TABLE_SOURCE_NAME, t.TABLE_SCHEMA, t.TABLE_NAME,
                t.KIND, t.TOUCHED_AT, t.TABLE_SOURCE_REF, t.TABLE_SCHEMA_REF, t.TABLE_NAME_REF)
            .select(selectDistinct(tr.GRAPH_NAME, tr.COORDINATE, tr.TABLE_SOURCE_NAME, tr.TABLE_SCHEMA,
                    tr.TABLE_NAME, tr.KIND, val(touchedAt, t.TOUCHED_AT), st.SOURCE_NAME, st.TABLE_SCHEMA,
                    st.TABLE_NAME)
                .from(tr)
                .join(o).on(o.GRAPH_NAME.eq(tr.GRAPH_NAME), o.COORDINATE.eq(tr.COORDINATE))
                .leftJoin(st).on(st.SOURCE_NAME.eq(tr.TABLE_SOURCE_NAME), st.TABLE_SCHEMA.eq(tr.TABLE_SCHEMA),
                    st.TABLE_NAME.eq(tr.TABLE_NAME))
                .where(tr.GRAPH_NAME.eq(graph), o.DEPTH.eq(depth)))
            .onConflict(t.GRAPH_NAME, t.COORDINATE, t.TABLE_SOURCE_NAME, t.TABLE_SCHEMA, t.TABLE_NAME)
            .doUpdate()
            .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
            .set(t.TABLE_SOURCE_REF, excluded(t.TABLE_SOURCE_REF))
            .set(t.TABLE_SCHEMA_REF, excluded(t.TABLE_SCHEMA_REF))
            .set(t.TABLE_NAME_REF, excluded(t.TABLE_NAME_REF))
            .execute();

        // A class is referenced as the graph resolves its name, the copy on the first entry of the
        // graph's classpath declaring it.
        var b = GRAPHITRON_OCCURRENCE_SOURCE_OBJECT;
        var br = GRAPHITRON_OCCURRENCE_SOURCE_OBJECT_RULE;
        var oc = CODE_GRAPH_CLASS.as("oc");
        dsl.insertInto(b)
            .columns(b.GRAPH_NAME, b.COORDINATE, b.KIND, b.CLASS_NAME, b.TOUCHED_AT,
                b.CLASS_SOURCE_REF, b.CLASS_NAME_REF)
            .select(selectDistinct(br.GRAPH_NAME, br.COORDINATE, br.KIND, br.CLASS_NAME,
                    val(touchedAt, b.TOUCHED_AT), oc.SOURCE_NAME, oc.CLASS_NAME)
                .from(br)
                .join(o).on(o.GRAPH_NAME.eq(br.GRAPH_NAME), o.COORDINATE.eq(br.COORDINATE))
                .leftJoin(oc).on(oc.GRAPH_NAME.eq(br.GRAPH_NAME), oc.CLASS_NAME.eq(br.CLASS_NAME))
                .where(br.GRAPH_NAME.eq(graph), o.DEPTH.eq(depth)))
            .onConflict(b.GRAPH_NAME, b.COORDINATE)
            .doUpdate()
            .set(b.CLASS_NAME, excluded(b.CLASS_NAME))
            .set(b.TOUCHED_AT, excluded(b.TOUCHED_AT))
            .set(b.CLASS_SOURCE_REF, excluded(b.CLASS_SOURCE_REF))
            .set(b.CLASS_NAME_REF, excluded(b.CLASS_NAME_REF))
            .execute();
    }

    /**
     * Deletes the rows of the occurrences at {@code depth}, or of every occurrence where it is null,
     * this reading did not mark, subtypes before the source they hang on.
     */
    public static void sweep(DSLContext dsl, String graph, Integer depth, LocalDateTime touchedAt) {
        var b = GRAPHITRON_OCCURRENCE_SOURCE_OBJECT;
        dsl.deleteFrom(b)
            .where(b.GRAPH_NAME.eq(graph))
            .and(b.TOUCHED_AT.ne(touchedAt))
            .and(OccurrenceProducers.atDepth(b.GRAPH_NAME, b.COORDINATE, depth))
            .execute();
        var t = GRAPHITRON_OCCURRENCE_SOURCE_TABLE;
        dsl.deleteFrom(t)
            .where(t.GRAPH_NAME.eq(graph))
            .and(t.TOUCHED_AT.ne(touchedAt))
            .and(OccurrenceProducers.atDepth(t.GRAPH_NAME, t.COORDINATE, depth))
            .execute();
        var s = GRAPHITRON_OCCURRENCE_SOURCE;
        dsl.deleteFrom(s)
            .where(s.GRAPH_NAME.eq(graph))
            .and(s.TOUCHED_AT.ne(touchedAt))
            .and(OccurrenceProducers.atDepth(s.GRAPH_NAME, s.COORDINATE, depth))
            .execute();
    }
}
