package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;

import java.time.LocalDateTime;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_BINDING;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_CALL_PARAMETER;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_CALL_PARAMETER_RULE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_PARAMETER;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_PRODUCER_SERVICE;
import static org.jooq.impl.DSL.excluded;
import static org.jooq.impl.DSL.inline;
import static org.jooq.impl.DSL.val;

/**
 * Writes {@code graphitron_occurrence_call_parameter}: what fills each parameter of each service
 * call, from the rule view beside it, and for each {@code ARGUMENT} fill the {@code PARAMETER} row of
 * {@code graphitron_occurrence_binding} at the occurrence taken, marked and swept.
 *
 * <p>Runs after the producers and their calls, whose methods it reads the parameters of, the
 * argMapping landings and the context keys, which are the written fills, and the sources, which
 * decide whether a call can be handed one. The binding's {@code bound} spells the parameter as
 * {@code class#method(descriptor):position}, from the call's resolved method.
 */
public final class OccurrenceCallParameters {

    private OccurrenceCallParameters() {}

    /** States the graph's call parameters and their bindings, and sweeps what the reading no longer finds. */
    public static void derive(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        var c = GRAPHITRON_OCCURRENCE_CALL_PARAMETER;
        var r = GRAPHITRON_OCCURRENCE_CALL_PARAMETER_RULE;
        dsl.insertInto(c)
            .columns(c.GRAPH_NAME, c.COORDINATE, c.POSITION, c.PARAMETER_NAME, c.FILL,
                c.LANDING_COORDINATE, c.CONTEXT_KEY, c.SESSION_MEMBER, c.TOUCHED_AT)
            .select(dsl
                .select(r.GRAPH_NAME, r.COORDINATE, r.POSITION, r.PARAMETER_NAME, r.FILL,
                    r.LANDING_COORDINATE, r.CONTEXT_KEY, r.SESSION_MEMBER, val(touchedAt, c.TOUCHED_AT))
                .from(r)
                .where(r.GRAPH_NAME.eq(graph)))
            .onConflict(c.GRAPH_NAME, c.COORDINATE, c.POSITION)
            .doUpdate()
            .set(c.PARAMETER_NAME, excluded(c.PARAMETER_NAME))
            .set(c.FILL, excluded(c.FILL))
            .set(c.LANDING_COORDINATE, excluded(c.LANDING_COORDINATE))
            .set(c.CONTEXT_KEY, excluded(c.CONTEXT_KEY))
            .set(c.SESSION_MEMBER, excluded(c.SESSION_MEMBER))
            .set(c.TOUCHED_AT, excluded(c.TOUCHED_AT))
            .execute();
        dsl.deleteFrom(c)
            .where(c.GRAPH_NAME.eq(graph))
            .and(c.TOUCHED_AT.ne(touchedAt))
            .execute();
        bindings(dsl, graph, touchedAt);
    }

    /** The {@code PARAMETER} bindings, one per {@code ARGUMENT} fill, at the occurrence it takes. */
    private static void bindings(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        var b = GRAPHITRON_OCCURRENCE_BINDING;
        var p = GRAPHITRON_OCCURRENCE_PARAMETER;
        var c = GRAPHITRON_OCCURRENCE_CALL_PARAMETER;
        var s = GRAPHITRON_OCCURRENCE_PRODUCER_SERVICE;
        var bound = s.METHOD_CLASS_NAME.concat(inline("#"), s.METHOD_NAME, s.METHOD_DESCRIPTOR,
            inline(":"), c.POSITION.cast(String.class));
        var taken = dsl
            .select(c.GRAPH_NAME, c.LANDING_COORDINATE, bound.as("bound"), c.COORDINATE, c.POSITION)
            .from(c)
            .join(s).on(s.GRAPH_NAME.eq(c.GRAPH_NAME), s.COORDINATE.eq(c.COORDINATE))
            .where(c.GRAPH_NAME.eq(graph), c.FILL.eq(inline("ARGUMENT")))
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
            .columns(p.GRAPH_NAME, p.COORDINATE, p.BOUND, p.KIND, p.CALL_COORDINATE, p.POSITION,
                p.TOUCHED_AT)
            .select(dsl.select(graphName, coordinate, boundName, inline("PARAMETER"),
                    taken.field(c.COORDINATE), taken.field(c.POSITION), val(touchedAt, p.TOUCHED_AT))
                .from(taken))
            .onConflict(p.GRAPH_NAME, p.COORDINATE, p.BOUND)
            .doUpdate()
            .set(p.CALL_COORDINATE, excluded(p.CALL_COORDINATE))
            .set(p.POSITION, excluded(p.POSITION))
            .set(p.TOUCHED_AT, excluded(p.TOUCHED_AT))
            .execute();
        dsl.deleteFrom(b)
            .where(b.GRAPH_NAME.eq(graph))
            .and(b.KIND.eq("PARAMETER"))
            .and(b.TOUCHED_AT.ne(touchedAt))
            .execute();
    }
}
