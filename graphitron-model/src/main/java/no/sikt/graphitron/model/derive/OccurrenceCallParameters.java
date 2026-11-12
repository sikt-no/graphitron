package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Table;

import java.time.LocalDateTime;

import static no.sikt.graphitron.model.Tables.CODE_METHOD_PARAMETER;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_CALL_PARAMETER;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_CALL_PARAMETER_PAIR;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_CALL_PARAMETER_PAIR_RULE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_CALL_PARAMETER_RULE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_CONDITION_PARAMETER_RULE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_CALL_METHOD;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE;
import static no.sikt.graphitron.model.Tables.SQL_TABLE;
import static no.sikt.graphitron.model.Tables.STORE_GRAPH_SOURCE;
import static org.jooq.impl.DSL.coalesce;
import static org.jooq.impl.DSL.excluded;
import static org.jooq.impl.DSL.inline;
import static org.jooq.impl.DSL.val;

/**
 * Writes {@code graphitron_occurrence_call_parameter}: what fills each parameter of each call, a depth
 * at a time inside {@link OccurrenceDepths}, from the rule view beside it for the method the call
 * pinned, marked and swept after the last depth. An {@code ARGUMENT} fill names the occurrence it
 * takes by its {@code landing_coordinate}, which is where a reader at an argument asks for its
 * parameter. An {@code ARGUMENT} fill whose parameter takes a table's generated jOOQ record, itself or as
 * its element type, carries that table: the occurrence taken lands there.
 *
 * <p>Runs after the depth's producers, whose calls the fills decide the pin of, and before what they
 * produce and the depth's sources: a call's source is inferred from its producer starting as a
 * child, never read off the sources, so the fills of a depth's calls need nothing written after
 * them.
 */
public final class OccurrenceCallParameters {

    private OccurrenceCallParameters() {}

    /**
     * States what fills each parameter of the calls the occurrences at {@code depth} make, the method
     * each pinned.
     */
    public static void writeDepth(DSLContext dsl, String graph, int depth, LocalDateTime touchedAt) {
        write(dsl, graph, depth, touchedAt, GRAPHITRON_OCCURRENCE_CALL_PARAMETER_RULE);
        pairs(dsl, graph, depth, touchedAt);
    }

    /**
     * States what fills each parameter of the {@code @condition} calls the occurrences at
     * {@code depth} make, after the depth's bindings decide which tables they are applied to.
     */
    public static void writeConditions(DSLContext dsl, String graph, int depth, LocalDateTime touchedAt) {
        write(dsl, graph, depth, touchedAt, GRAPHITRON_OCCURRENCE_CONDITION_PARAMETER_RULE);
        pairs(dsl, graph, depth, touchedAt);
    }

    /**
     * The written pair each {@code ARGUMENT} fill of the calls at {@code depth} came from, read off the
     * fills just written and the landings the argMapping stage wrote.
     */
    private static void pairs(DSLContext dsl, String graph, int depth, LocalDateTime touchedAt) {
        var t = GRAPHITRON_OCCURRENCE_CALL_PARAMETER_PAIR;
        var r = GRAPHITRON_OCCURRENCE_CALL_PARAMETER_PAIR_RULE;
        var o = GRAPHITRON_OCCURRENCE;
        dsl.insertInto(t)
            .columns(t.GRAPH_NAME, t.COORDINATE, t.CALL, t.POSITION, t.PAIR_COORDINATE, t.DIRECTIVE_NAME,
                t.ORDINAL, t.OWNER, t.PARAMETER_NAME, t.CALL_COORDINATE, t.TOUCHED_AT)
            .select(dsl.select(r.GRAPH_NAME, r.COORDINATE, r.CALL, r.POSITION, r.PAIR_COORDINATE,
                    r.DIRECTIVE_NAME, r.ORDINAL, r.OWNER, r.PARAMETER_NAME, r.CALL_COORDINATE,
                    val(touchedAt, t.TOUCHED_AT))
                .from(r)
                .join(o).on(o.GRAPH_NAME.eq(r.GRAPH_NAME), o.COORDINATE.eq(r.COORDINATE))
                .where(r.GRAPH_NAME.eq(graph), o.DEPTH.eq(depth)))
            .onConflict(t.GRAPH_NAME, t.COORDINATE, t.CALL, t.POSITION)
            .doUpdate()
            .set(t.PAIR_COORDINATE, excluded(t.PAIR_COORDINATE))
            .set(t.DIRECTIVE_NAME, excluded(t.DIRECTIVE_NAME))
            .set(t.ORDINAL, excluded(t.ORDINAL))
            .set(t.OWNER, excluded(t.OWNER))
            .set(t.PARAMETER_NAME, excluded(t.PARAMETER_NAME))
            .set(t.CALL_COORDINATE, excluded(t.CALL_COORDINATE))
            .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
            .execute();
    }

    /** The fills {@code rule} states for the pinned methods of the calls at {@code depth}. */
    private static void write(DSLContext dsl, String graph, int depth, LocalDateTime touchedAt,
                              Table<?> rule) {
        var c = GRAPHITRON_OCCURRENCE_CALL_PARAMETER;
        var r = RuleColumns.of(rule);
        var made = GRAPHITRON_OCCURRENCE_CALL_METHOD;
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
                .from(rule)
                // The fills of the method the call pinned, of the calls at this depth.
                .join(made).on(made.GRAPH_NAME.eq(r.GRAPH_NAME), made.COORDINATE.eq(r.COORDINATE),
                    made.CALL.eq(r.CALL), made.METHOD_DESCRIPTOR.eq(r.METHOD_DESCRIPTOR))
                .join(o).on(o.GRAPH_NAME.eq(r.GRAPH_NAME), o.COORDINATE.eq(r.COORDINATE))
                // The table, in the graph's catalog, whose generated record an argument's parameter takes.
                .leftJoin(mp).on(mp.SOURCE_NAME.eq(made.METHOD_SOURCE_NAME),
                    mp.CLASS_NAME.eq(made.METHOD_CLASS_NAME), mp.METHOD_NAME.eq(made.METHOD_NAME),
                    mp.DESCRIPTOR.eq(made.METHOD_DESCRIPTOR), mp.POSITION.eq(r.POSITION),
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
    }

    /** Deletes the fills no depth of this reading marked. */
    public static void sweep(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        var pair = GRAPHITRON_OCCURRENCE_CALL_PARAMETER_PAIR;
        dsl.deleteFrom(pair)
            .where(pair.GRAPH_NAME.eq(graph))
            .and(pair.TOUCHED_AT.ne(touchedAt))
            .execute();
        var c = GRAPHITRON_OCCURRENCE_CALL_PARAMETER;
        dsl.deleteFrom(c)
            .where(c.GRAPH_NAME.eq(graph))
            .and(c.TOUCHED_AT.ne(touchedAt))
            .execute();
    }

    /** A fill rule's columns, read by name: every fill rule has graphitron_occurrence_call_parameter_rule's shape. */
    private record RuleColumns(Field<String> GRAPH_NAME, Field<String> COORDINATE, Field<String> CALL,
                               Field<String> CALL_KIND, Field<String> METHOD_DESCRIPTOR,
                               Field<Integer> POSITION, Field<String> PARAMETER_NAME, Field<String> FILL,
                               Field<String> LANDING_COORDINATE, Field<String> CONTEXT_KEY,
                               Field<String> SESSION_MEMBER) {
        static RuleColumns of(Table<?> rule) {
            return new RuleColumns(rule.field("GRAPH_NAME", String.class), rule.field("COORDINATE", String.class),
                rule.field("CALL", String.class), rule.field("CALL_KIND", String.class),
                rule.field("METHOD_DESCRIPTOR", String.class), rule.field("POSITION", Integer.class),
                rule.field("PARAMETER_NAME", String.class), rule.field("FILL", String.class),
                rule.field("LANDING_COORDINATE", String.class), rule.field("CONTEXT_KEY", String.class),
                rule.field("SESSION_MEMBER", String.class));
        }
    }
}
