package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Table;

import java.time.LocalDateTime;

import static no.sikt.graphitron.model.Tables.CODE_METHOD_PARAMETER;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_BINDING;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_BINDING_PARAMETER;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_BINDING_PARAMETER_RULE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_CALL_PARAMETER;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_CALL_PARAMETER_PAIR;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_CALL_PARAMETER_PAIR_RULE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_CALL_PARAMETER_RULE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_CONDITION_PARAMETER_RULE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_CALL_METHOD;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_WRITE_PARAMETER_RULE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE;
import static no.sikt.graphitron.model.Tables.SQL_TABLE;
import static no.sikt.graphitron.model.Tables.STORE_GRAPH_SOURCE;
import static org.jooq.impl.DSL.coalesce;
import static org.jooq.impl.DSL.excluded;
import static org.jooq.impl.DSL.inline;
import static org.jooq.impl.DSL.selectOne;
import static org.jooq.impl.DSL.val;

/**
 * Writes {@code graphitron_occurrence_call_parameter}: what fills each parameter of each call, from
 * the rule view beside it for the method the call pinned, marked and swept. An {@code ARGUMENT} fill
 * names the occurrence it takes by its {@code landing_coordinate}, which is where a reader at an
 * argument asks for its parameter. An {@code ARGUMENT} fill whose parameter takes a table's generated
 * jOOQ record, itself or as its element type, carries that table: the occurrence taken lands there.
 *
 * <p>Each {@code ARGUMENT} fill is also the {@code PARAMETER} binding of the occurrence it takes, into
 * {@code graphitron_occurrence_binding} and {@code graphitron_occurrence_binding_parameter}, written
 * beside the fill.
 *
 * <p>Two stages own the rows, by call kind. The service and write calls' are {@link OccurrenceProducers}', ahead
 * of {@link OccurrenceDepths}, whose sources read a record a service takes; the condition calls' are
 * {@link OccurrenceBindings}', after the depths, since a condition's call waits on the bindings. Each
 * writes and sweeps its own kind.
 */
public final class OccurrenceCallParameters {

    private OccurrenceCallParameters() {}

    private static final String PARAMETER = "PARAMETER";

    /** States what fills each parameter of the service calls, the method each pinned. */
    public static void writeServices(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        unbind(dsl, graph, GRAPHITRON_OCCURRENCE_CALL_PARAMETER_RULE, "SERVICE");
        write(dsl, graph, touchedAt, GRAPHITRON_OCCURRENCE_CALL_PARAMETER_RULE);
        pairs(dsl, graph, touchedAt, "SERVICE");
        bindings(dsl, graph, touchedAt, "SERVICE");
    }

    /**
     * States what fills each parameter of the write calls: each argument of the write's field, an input
     * object bound against the record of the table written. A write pins no method, so its fills are
     * the rule's rows as they stand.
     */
    public static void writeWrites(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        var p = GRAPHITRON_OCCURRENCE_BINDING_PARAMETER;
        var r = GRAPHITRON_OCCURRENCE_WRITE_PARAMETER_RULE;
        dsl.deleteFrom(p)
            .where(p.GRAPH_NAME.eq(graph), p.CALL_KIND.eq("WRITE"))
            .andNotExists(selectOne().from(r)
                .where(r.GRAPH_NAME.eq(p.GRAPH_NAME), r.COORDINATE.eq(p.CALL_COORDINATE), r.CALL.eq(p.CALL),
                    r.POSITION.eq(p.POSITION), r.LANDING_COORDINATE.eq(p.COORDINATE)))
            .execute();
        var c = GRAPHITRON_OCCURRENCE_CALL_PARAMETER;
        dsl.insertInto(c)
            .columns(c.GRAPH_NAME, c.COORDINATE, c.CALL, c.CALL_KIND, c.POSITION, c.PARAMETER_NAME, c.FILL,
                c.LANDING_COORDINATE, c.RECORD_SOURCE_NAME, c.RECORD_SCHEMA, c.RECORD_TABLE, c.TOUCHED_AT,
                c.RECORD_SOURCE_REF, c.RECORD_SCHEMA_REF, c.RECORD_TABLE_REF)
            .select(dsl.select(r.GRAPH_NAME, r.COORDINATE, r.CALL, r.CALL_KIND, r.POSITION, r.PARAMETER_NAME,
                    r.FILL, r.LANDING_COORDINATE, r.RECORD_SOURCE_NAME, r.RECORD_SCHEMA, r.RECORD_TABLE,
                    val(touchedAt, c.TOUCHED_AT), r.RECORD_SOURCE_NAME, r.RECORD_SCHEMA, r.RECORD_TABLE)
                .from(r)
                .where(r.GRAPH_NAME.eq(graph)))
            .onConflict(c.GRAPH_NAME, c.COORDINATE, c.CALL, c.POSITION)
            .doUpdate()
            .set(c.CALL_KIND, excluded(c.CALL_KIND))
            .set(c.PARAMETER_NAME, excluded(c.PARAMETER_NAME))
            .set(c.FILL, excluded(c.FILL))
            .set(c.LANDING_COORDINATE, excluded(c.LANDING_COORDINATE))
            .set(c.CONTEXT_KEY, (String) null)
            .set(c.SESSION_MEMBER, (String) null)
            .set(c.RECORD_SOURCE_NAME, excluded(c.RECORD_SOURCE_NAME))
            .set(c.RECORD_SCHEMA, excluded(c.RECORD_SCHEMA))
            .set(c.RECORD_TABLE, excluded(c.RECORD_TABLE))
            .set(c.TOUCHED_AT, excluded(c.TOUCHED_AT))
            .set(c.RECORD_SOURCE_REF, excluded(c.RECORD_SOURCE_REF))
            .set(c.RECORD_SCHEMA_REF, excluded(c.RECORD_SCHEMA_REF))
            .set(c.RECORD_TABLE_REF, excluded(c.RECORD_TABLE_REF))
            .execute();
        bindings(dsl, graph, touchedAt, "WRITE");
    }

    /**
     * States what fills each parameter of the {@code @condition} calls, after the bindings decide which
     * tables they are applied to.
     */
    public static void writeConditions(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        unbind(dsl, graph, GRAPHITRON_OCCURRENCE_CONDITION_PARAMETER_RULE, "CONDITION");
        write(dsl, graph, touchedAt, GRAPHITRON_OCCURRENCE_CONDITION_PARAMETER_RULE);
        pairs(dsl, graph, touchedAt, "CONDITION");
        bindings(dsl, graph, touchedAt, "CONDITION");
    }

    /**
     * Deletes the parameter bindings of the calls of {@code callKind} that {@code rule} no longer
     * states for the method each call pinned, before the fills they reference are rewritten: a
     * parameter now supplied by another occurrence, or by no argument, would otherwise hold its old
     * fill in place.
     */
    private static void unbind(DSLContext dsl, String graph, Table<?> rule, String callKind) {
        var p = GRAPHITRON_OCCURRENCE_BINDING_PARAMETER;
        var r = RuleColumns.of(rule);
        var made = GRAPHITRON_OCCURRENCE_CALL_METHOD;
        dsl.deleteFrom(p)
            .where(p.GRAPH_NAME.eq(graph), p.CALL_KIND.eq(callKind))
            .andNotExists(selectOne().from(rule)
                .join(made).on(made.GRAPH_NAME.eq(r.GRAPH_NAME), made.COORDINATE.eq(r.COORDINATE),
                    made.CALL.eq(r.CALL), made.METHOD_DESCRIPTOR.eq(r.METHOD_DESCRIPTOR))
                .where(r.GRAPH_NAME.eq(p.GRAPH_NAME), r.COORDINATE.eq(p.CALL_COORDINATE), r.CALL.eq(p.CALL),
                    r.POSITION.eq(p.POSITION), r.FILL.eq(inline("ARGUMENT")),
                    r.LANDING_COORDINATE.eq(p.COORDINATE)))
            .execute();
    }

    /**
     * The {@code PARAMETER} binding of each occurrence an {@code ARGUMENT} fill of the calls of
     * {@code callKind} takes, read off the fills just written.
     */
    private static void bindings(DSLContext dsl, String graph, LocalDateTime touchedAt, String callKind) {
        var b = GRAPHITRON_OCCURRENCE_BINDING;
        var p = GRAPHITRON_OCCURRENCE_BINDING_PARAMETER;
        var r = GRAPHITRON_OCCURRENCE_BINDING_PARAMETER_RULE;
        dsl.insertInto(b)
            .columns(b.GRAPH_NAME, b.COORDINATE, b.BOUND, b.KIND, b.TOUCHED_AT)
            .select(dsl.select(r.GRAPH_NAME, r.COORDINATE, r.BOUND, inline(PARAMETER), val(touchedAt, b.TOUCHED_AT))
                .from(r)
                .where(r.GRAPH_NAME.eq(graph), r.CALL_KIND.eq(callKind)))
            .onConflict(b.GRAPH_NAME, b.COORDINATE, b.BOUND)
            .doUpdate()
            .set(b.TOUCHED_AT, excluded(b.TOUCHED_AT))
            .execute();
        dsl.insertInto(p)
            .columns(p.GRAPH_NAME, p.COORDINATE, p.CALL_COORDINATE, p.CALL, p.POSITION, p.CALL_KIND, p.FILL,
                p.KIND, p.TOUCHED_AT)
            .select(dsl.select(r.GRAPH_NAME, r.COORDINATE, r.CALL_COORDINATE, r.CALL, r.POSITION, r.CALL_KIND,
                    r.FILL, inline(PARAMETER), val(touchedAt, p.TOUCHED_AT))
                .from(r)
                .where(r.GRAPH_NAME.eq(graph), r.CALL_KIND.eq(callKind)))
            // The parameter's own key, every column of it inserted: the primary key's bound is
            // generated, so a conflict on it never matches and a second reading would insert again.
            .onConflict(p.GRAPH_NAME, p.CALL_COORDINATE, p.CALL, p.POSITION)
            .doUpdate()
            .set(p.TOUCHED_AT, excluded(p.TOUCHED_AT))
            .execute();
    }

    /**
     * The written pair each {@code ARGUMENT} fill of the calls of {@code callKind} came from, read off
     * the fills just written and the landings the argMapping stage wrote.
     */
    private static void pairs(DSLContext dsl, String graph, LocalDateTime touchedAt, String callKind) {
        var t = GRAPHITRON_OCCURRENCE_CALL_PARAMETER_PAIR;
        var r = GRAPHITRON_OCCURRENCE_CALL_PARAMETER_PAIR_RULE;
        var c = GRAPHITRON_OCCURRENCE_CALL_PARAMETER;
        dsl.insertInto(t)
            .columns(t.GRAPH_NAME, t.COORDINATE, t.CALL, t.POSITION, t.PAIR_COORDINATE, t.DIRECTIVE_NAME,
                t.ORDINAL, t.OWNER, t.PARAMETER_NAME, t.TOUCHED_AT)
            .select(dsl.select(r.GRAPH_NAME, r.COORDINATE, r.CALL, r.POSITION, r.PAIR_COORDINATE,
                    r.DIRECTIVE_NAME, r.ORDINAL, r.OWNER, r.PARAMETER_NAME, val(touchedAt, t.TOUCHED_AT))
                .from(r)
                .join(c).on(c.GRAPH_NAME.eq(r.GRAPH_NAME), c.COORDINATE.eq(r.COORDINATE), c.CALL.eq(r.CALL),
                    c.POSITION.eq(r.POSITION))
                .where(r.GRAPH_NAME.eq(graph), c.CALL_KIND.eq(callKind), c.TOUCHED_AT.eq(touchedAt)))
            .onConflict(t.GRAPH_NAME, t.COORDINATE, t.CALL, t.POSITION)
            .doUpdate()
            .set(t.PAIR_COORDINATE, excluded(t.PAIR_COORDINATE))
            .set(t.DIRECTIVE_NAME, excluded(t.DIRECTIVE_NAME))
            .set(t.ORDINAL, excluded(t.ORDINAL))
            .set(t.OWNER, excluded(t.OWNER))
            .set(t.PARAMETER_NAME, excluded(t.PARAMETER_NAME))
            .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
            .execute();
    }

    /** The fills {@code rule} states for the pinned methods of the calls. */
    private static void write(DSLContext dsl, String graph, LocalDateTime touchedAt, Table<?> rule) {
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
                // The fills of the method the call pinned.
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
                .where(r.GRAPH_NAME.eq(graph)))
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

    /**
     * Deletes the fills of the calls of {@code callKind} this reading did not mark, their pairs first,
     * then the parameter bindings of those calls the reading did not mark. A {@code PARAMETER} binding
     * left with no parameter beneath it is stale whichever stage wrote it, the cascade having taken its
     * parameter.
     */
    public static void sweep(DSLContext dsl, String graph, LocalDateTime touchedAt, String callKind) {
        var pair = GRAPHITRON_OCCURRENCE_CALL_PARAMETER_PAIR;
        var c = GRAPHITRON_OCCURRENCE_CALL_PARAMETER;
        dsl.deleteFrom(pair)
            .where(pair.GRAPH_NAME.eq(graph))
            .and(pair.TOUCHED_AT.ne(touchedAt))
            .andExists(selectOne().from(c)
                .where(c.GRAPH_NAME.eq(pair.GRAPH_NAME), c.COORDINATE.eq(pair.COORDINATE), c.CALL.eq(pair.CALL),
                    c.POSITION.eq(pair.POSITION), c.CALL_KIND.eq(callKind)))
            .execute();
        dsl.deleteFrom(c)
            .where(c.GRAPH_NAME.eq(graph))
            .and(c.CALL_KIND.eq(callKind))
            .and(c.TOUCHED_AT.ne(touchedAt))
            .execute();
        var p = GRAPHITRON_OCCURRENCE_BINDING_PARAMETER;
        dsl.deleteFrom(p)
            .where(p.GRAPH_NAME.eq(graph))
            .and(p.CALL_KIND.eq(callKind))
            .and(p.TOUCHED_AT.ne(touchedAt))
            .execute();
        var b = GRAPHITRON_OCCURRENCE_BINDING;
        dsl.deleteFrom(b)
            .where(b.GRAPH_NAME.eq(graph))
            .and(b.KIND.eq(PARAMETER))
            .andNotExists(selectOne().from(p)
                .where(p.GRAPH_NAME.eq(b.GRAPH_NAME), p.COORDINATE.eq(b.COORDINATE), p.BOUND.eq(b.BOUND)))
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
