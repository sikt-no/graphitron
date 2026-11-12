package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;

import java.time.LocalDateTime;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_BINDING;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_BINDING_TABLE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_BINDING_TABLE_RULE;
import static org.jooq.impl.DSL.excluded;
import static org.jooq.impl.DSL.inline;
import static org.jooq.impl.DSL.val;

/**
 * Writes {@code graphitron_occurrence_binding_table}: the table each occurrence's rows or value land
 * on, from the rule view beside it, under a {@code TABLE} row of {@code graphitron_occurrence_binding},
 * marked and swept.
 *
 * <p>A step of {@link OccurrenceBindings}, after {@link OccurrenceDepths}' join paths, whose targets
 * are the landings, and the node id and column bindings, which an argument or input field binding a
 * table must not have; it reads stored rows only, the producers' results and the participants
 * included, and adds no rule to {@link PathNeeds}.
 */
public final class OccurrenceTables {

    private OccurrenceTables() {}

    /** States the table bindings of the occurrences. */
    public static void write(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        var o = GRAPHITRON_OCCURRENCE;
        var b = GRAPHITRON_OCCURRENCE_BINDING;
        var t = GRAPHITRON_OCCURRENCE_BINDING_TABLE;
        var r = GRAPHITRON_OCCURRENCE_BINDING_TABLE_RULE;
        dsl.insertInto(b)
            .columns(b.GRAPH_NAME, b.COORDINATE, b.BOUND, b.KIND, b.TOUCHED_AT)
            .select(dsl
                .select(r.GRAPH_NAME, r.COORDINATE, r.BOUND, inline("TABLE"), val(touchedAt, b.TOUCHED_AT))
                .from(r)
                .join(o).on(o.GRAPH_NAME.eq(r.GRAPH_NAME), o.COORDINATE.eq(r.COORDINATE))
                .where(r.GRAPH_NAME.eq(graph)))
            .onConflict(b.GRAPH_NAME, b.COORDINATE, b.BOUND)
            .doUpdate()
            .set(b.TOUCHED_AT, excluded(b.TOUCHED_AT))
            .execute();
        dsl.insertInto(t)
            .columns(t.GRAPH_NAME, t.COORDINATE, t.BOUND, t.KIND, t.TABLE_SOURCE_NAME, t.TABLE_SCHEMA,
                t.TABLE_NAME, t.TOUCHED_AT)
            .select(dsl
                .select(r.GRAPH_NAME, r.COORDINATE, r.BOUND, inline("TABLE"), r.TABLE_SOURCE_NAME,
                    r.TABLE_SCHEMA, r.TABLE_NAME, val(touchedAt, t.TOUCHED_AT))
                .from(r)
                .join(o).on(o.GRAPH_NAME.eq(r.GRAPH_NAME), o.COORDINATE.eq(r.COORDINATE))
                .where(r.GRAPH_NAME.eq(graph)))
            .onConflict(t.GRAPH_NAME, t.COORDINATE, t.BOUND)
            .doUpdate()
            .set(t.TABLE_SOURCE_NAME, excluded(t.TABLE_SOURCE_NAME))
            .set(t.TABLE_SCHEMA, excluded(t.TABLE_SCHEMA))
            .set(t.TABLE_NAME, excluded(t.TABLE_NAME))
            .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
            .execute();
    }

    /** Deletes the {@code TABLE} bindings this reading did not mark, their subtype rows by the cascade. */
    public static void sweep(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        var b = GRAPHITRON_OCCURRENCE_BINDING;
        dsl.deleteFrom(b)
            .where(b.GRAPH_NAME.eq(graph))
            .and(b.KIND.eq("TABLE"))
            .and(b.TOUCHED_AT.ne(touchedAt))
            .execute();
    }
}
