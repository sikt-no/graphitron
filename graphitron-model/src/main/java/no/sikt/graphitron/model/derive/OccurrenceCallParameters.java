package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;

import java.time.LocalDateTime;

import static no.sikt.graphitron.model.Tables.CODE_METHOD_PARAMETER;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_BINDING;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_CALL_PARAMETER;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_CALL_PARAMETER_RULE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_CALL_PIN;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_PARAMETER;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_PRODUCER_SERVICE;
import static no.sikt.graphitron.model.Tables.SQL_TABLE;
import static no.sikt.graphitron.model.Tables.STORE_GRAPH_SOURCE;
import static org.jooq.impl.DSL.coalesce;
import static org.jooq.impl.DSL.excluded;
import static org.jooq.impl.DSL.inline;
import static org.jooq.impl.DSL.val;

/**
 * Writes {@code graphitron_occurrence_call_parameter}: what fills each parameter of each call, a depth
 * at a time inside {@link OccurrenceDepths}, from the rule view beside it for the method the call
 * pinned, and for each {@code ARGUMENT} fill the {@code PARAMETER} row of
 * {@code graphitron_occurrence_binding} at the occurrence taken, marked and swept after the last
 * depth. An {@code ARGUMENT} fill whose parameter takes a table's generated jOOQ record, itself or as
 * its element type, carries that table: the occurrence taken lands there.
 *
 * <p>Runs after the depth's producers, whose calls the fills decide the pin of, and before what they
 * produce and the depth's sources: a call's source is inferred from its producer starting as a
 * child, never read off the sources, so the fills of a depth's calls need nothing written after
 * them. The binding's {@code bound} spells the parameter as {@code class#method(descriptor):position},
 * from the call's pinned method.
 */
public final class OccurrenceCallParameters {

    private OccurrenceCallParameters() {}

    /**
     * States what fills each parameter of the calls the occurrences at {@code depth} make, the method
     * each pinned, and the {@code PARAMETER} binding of each argument a fill takes.
     */
    public static void writeDepth(DSLContext dsl, String graph, int depth, LocalDateTime touchedAt) {
        var c = GRAPHITRON_OCCURRENCE_CALL_PARAMETER;
        var r = GRAPHITRON_OCCURRENCE_CALL_PARAMETER_RULE;
        var pin = GRAPHITRON_OCCURRENCE_CALL_PIN;
        var o = GRAPHITRON_OCCURRENCE;
        var mp = CODE_METHOD_PARAMETER;
        var st = SQL_TABLE;
        var gs = STORE_GRAPH_SOURCE;
        dsl.insertInto(c)
            .columns(c.GRAPH_NAME, c.COORDINATE, c.CALL, c.CALL_KIND, c.POSITION, c.PARAMETER_NAME, c.FILL,
                c.LANDING_COORDINATE, c.CONTEXT_KEY, c.SESSION_MEMBER, c.RECORD_SOURCE_NAME, c.RECORD_SCHEMA,
                c.RECORD_TABLE, c.TOUCHED_AT, c.RECORD_SOURCE_REF, c.RECORD_SCHEMA_REF, c.RECORD_TABLE_REF)
            .select(dsl
                .select(r.GRAPH_NAME, r.COORDINATE, r.CALL, r.CALL_KIND, r.POSITION, r.PARAMETER_NAME, r.FILL,
                    r.LANDING_COORDINATE, r.CONTEXT_KEY, r.SESSION_MEMBER, st.SOURCE_NAME, st.TABLE_SCHEMA,
                    st.TABLE_NAME, val(touchedAt, c.TOUCHED_AT), st.SOURCE_NAME, st.TABLE_SCHEMA,
                    st.TABLE_NAME)
                .from(r)
                // The fills of the method the call pinned, of the calls at this depth.
                .join(pin).on(pin.GRAPH_NAME.eq(r.GRAPH_NAME), pin.COORDINATE.eq(r.COORDINATE),
                    pin.CALL.eq(r.CALL), pin.METHOD_DESCRIPTOR.eq(r.METHOD_DESCRIPTOR))
                .join(o).on(o.GRAPH_NAME.eq(r.GRAPH_NAME), o.COORDINATE.eq(r.COORDINATE))
                // The table, in the graph's catalog, whose generated record an argument's parameter takes.
                .leftJoin(mp).on(mp.SOURCE_NAME.eq(pin.METHOD_SOURCE_NAME),
                    mp.CLASS_NAME.eq(pin.METHOD_CLASS_NAME), mp.METHOD_NAME.eq(pin.METHOD_NAME),
                    mp.DESCRIPTOR.eq(pin.METHOD_DESCRIPTOR), mp.POSITION.eq(r.POSITION),
                    r.FILL.eq(inline("ARGUMENT")))
                .leftJoin(st.join(gs).on(gs.SOURCE_NAME.eq(st.SOURCE_NAME)))
                    .on(st.RECORD_CLASS_FQN.eq(coalesce(mp.ELEMENT_CLASS, mp.ERASED_CLASS)),
                        st.RECORD_CLASS_FQN.ne(inline("org.jooq.Record")), gs.GRAPH_NAME.eq(r.GRAPH_NAME))
                .where(r.GRAPH_NAME.eq(graph), o.DEPTH.eq(depth)))
            .onConflict(c.GRAPH_NAME, c.COORDINATE, c.CALL, c.POSITION)
            .doUpdate()
            .set(c.CALL_KIND, excluded(c.CALL_KIND))
            .set(c.PARAMETER_NAME, excluded(c.PARAMETER_NAME))
            .set(c.FILL, excluded(c.FILL))
            .set(c.LANDING_COORDINATE, excluded(c.LANDING_COORDINATE))
            .set(c.CONTEXT_KEY, excluded(c.CONTEXT_KEY))
            .set(c.SESSION_MEMBER, excluded(c.SESSION_MEMBER))
            .set(c.RECORD_SOURCE_NAME, excluded(c.RECORD_SOURCE_NAME))
            .set(c.RECORD_SCHEMA, excluded(c.RECORD_SCHEMA))
            .set(c.RECORD_TABLE, excluded(c.RECORD_TABLE))
            .set(c.TOUCHED_AT, excluded(c.TOUCHED_AT))
            .set(c.RECORD_SOURCE_REF, excluded(c.RECORD_SOURCE_REF))
            .set(c.RECORD_SCHEMA_REF, excluded(c.RECORD_SCHEMA_REF))
            .set(c.RECORD_TABLE_REF, excluded(c.RECORD_TABLE_REF))
            .execute();
        bindings(dsl, graph, depth, touchedAt);
    }

    /** Deletes the fills and {@code PARAMETER} bindings no depth of this reading marked. */
    public static void sweep(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        var c = GRAPHITRON_OCCURRENCE_CALL_PARAMETER;
        dsl.deleteFrom(c)
            .where(c.GRAPH_NAME.eq(graph))
            .and(c.TOUCHED_AT.ne(touchedAt))
            .execute();
        var b = GRAPHITRON_OCCURRENCE_BINDING;
        dsl.deleteFrom(b)
            .where(b.GRAPH_NAME.eq(graph))
            .and(b.KIND.eq("PARAMETER"))
            .and(b.TOUCHED_AT.ne(touchedAt))
            .execute();
    }

    /** The {@code PARAMETER} bindings, one per {@code ARGUMENT} fill, at the occurrence it takes. */
    private static void bindings(DSLContext dsl, String graph, int depth, LocalDateTime touchedAt) {
        var b = GRAPHITRON_OCCURRENCE_BINDING;
        var p = GRAPHITRON_OCCURRENCE_PARAMETER;
        var c = GRAPHITRON_OCCURRENCE_CALL_PARAMETER;
        var s = GRAPHITRON_OCCURRENCE_PRODUCER_SERVICE;
        var o = GRAPHITRON_OCCURRENCE;
        var bound = s.METHOD_CLASS_NAME.concat(inline("#"), s.METHOD_NAME, s.METHOD_DESCRIPTOR,
            inline(":"), c.POSITION.cast(String.class));
        var taken = dsl
            .select(c.GRAPH_NAME, c.LANDING_COORDINATE, bound.as("bound"), c.COORDINATE, c.CALL, c.POSITION)
            .from(c)
            .join(s).on(s.GRAPH_NAME.eq(c.GRAPH_NAME), s.COORDINATE.eq(c.COORDINATE), s.CALL.eq(c.CALL))
            .join(o).on(o.GRAPH_NAME.eq(c.GRAPH_NAME), o.COORDINATE.eq(c.COORDINATE))
            .where(c.GRAPH_NAME.eq(graph), c.FILL.eq(inline("ARGUMENT")), o.DEPTH.eq(depth))
            .asTable("taken");
        var graphName = taken.field(c.GRAPH_NAME);
        var coordinate = taken.field(c.LANDING_COORDINATE);
        var boundName = taken.field("bound", String.class);
        dsl.insertInto(b)
            .columns(b.GRAPH_NAME, b.COORDINATE, b.BOUND, b.KIND, b.TOUCHED_AT)
            .select(dsl.select(graphName, coordinate, boundName, inline("PARAMETER"),
                    val(touchedAt, b.TOUCHED_AT))
                .from(taken))
            .onConflict(b.GRAPH_NAME, b.COORDINATE, b.BOUND)
            .doUpdate()
            .set(b.TOUCHED_AT, excluded(b.TOUCHED_AT))
            .execute();
        dsl.insertInto(p)
            .columns(p.GRAPH_NAME, p.COORDINATE, p.BOUND, p.KIND, p.CALL_COORDINATE, p.CALL, p.POSITION,
                p.TOUCHED_AT)
            .select(dsl.select(graphName, coordinate, boundName, inline("PARAMETER"),
                    taken.field(c.COORDINATE), taken.field(c.CALL), taken.field(c.POSITION),
                    val(touchedAt, p.TOUCHED_AT))
                .from(taken))
            .onConflict(p.GRAPH_NAME, p.COORDINATE, p.BOUND)
            .doUpdate()
            .set(p.CALL_COORDINATE, excluded(p.CALL_COORDINATE))
            .set(p.CALL, excluded(p.CALL))
            .set(p.POSITION, excluded(p.POSITION))
            .set(p.TOUCHED_AT, excluded(p.TOUCHED_AT))
            .execute();
    }
}
