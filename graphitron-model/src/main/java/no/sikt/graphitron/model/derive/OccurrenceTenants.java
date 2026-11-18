package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Table;

import java.time.LocalDateTime;
import java.util.List;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_BINDING;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_BINDING_TENANT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_BINDING_TENANT_RULE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_INPUT_TYPE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_LIST_ELEMENT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_OPTIONAL;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_TENANT_SLOT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_TENANT_SLOT_AUTHORED;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_TENANT_SLOT_AUTHORED_RULE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_TENANT_SLOT_NODE_ID_KEY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_TENANT_SLOT_NODE_ID_KEY_RULE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_TENANT_SLOT_RECORD_COLUMN;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_TENANT_SLOT_RECORD_COLUMN_RULE;
import static org.jooq.impl.DSL.coalesce;
import static org.jooq.impl.DSL.excluded;
import static org.jooq.impl.DSL.exists;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.inline;
import static org.jooq.impl.DSL.not;
import static org.jooq.impl.DSL.select;
import static org.jooq.impl.DSL.selectOne;
import static org.jooq.impl.DSL.val;

/**
 * The {@code occurrence-tenants} stage: the call each {@code $tenant} pair or {@code @tenant} names the
 * tenant of, into {@code graphitron_occurrence_binding_tenant}, then every value each call's tenant is
 * read from, into {@code graphitron_occurrence_tenant_slot} and the subtype naming what the slot rests
 * on, then the stage's own defects; each marked and swept.
 *
 * <p>Runs after {@link OccurrenceBuilds}, off the landings, the column and node id bindings and the
 * classes the inputs are built into, all upstream of it.
 */
public final class OccurrenceTenants {

    private OccurrenceTenants() {}

    private static final String TENANT = "TENANT";

    /** States the graph's tenant bindings, slots and defects, and sweeps what the reading no longer finds. */
    public static void derive(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        bindings(dsl, graph, touchedAt);
        slots(dsl, graph, touchedAt, GRAPHITRON_OCCURRENCE_TENANT_SLOT_AUTHORED_RULE,
            GRAPHITRON_OCCURRENCE_TENANT_SLOT_AUTHORED, List.of());
        slots(dsl, graph, touchedAt, GRAPHITRON_OCCURRENCE_TENANT_SLOT_RECORD_COLUMN_RULE,
            GRAPHITRON_OCCURRENCE_TENANT_SLOT_RECORD_COLUMN, List.of("BOUND"));
        slots(dsl, graph, touchedAt, GRAPHITRON_OCCURRENCE_TENANT_SLOT_NODE_ID_KEY_RULE,
            GRAPHITRON_OCCURRENCE_TENANT_SLOT_NODE_ID_KEY, List.of("BOUND", "KEY_POSITION:POSITION"));
        var s = GRAPHITRON_OCCURRENCE_TENANT_SLOT;
        dsl.deleteFrom(s).where(s.GRAPH_NAME.eq(graph), s.TOUCHED_AT.ne(touchedAt)).execute();
        var b = GRAPHITRON_OCCURRENCE_BINDING;
        dsl.deleteFrom(b).where(b.GRAPH_NAME.eq(graph), b.KIND.eq(TENANT), b.TOUCHED_AT.ne(touchedAt)).execute();
        EntryDefects.derive(dsl, graph, touchedAt, EntryDefects.TENANTS);
    }

    /** The {@code TENANT} binding of each value an author names a call's tenant by, and its supertype row. */
    private static void bindings(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        var b = GRAPHITRON_OCCURRENCE_BINDING;
        var t = GRAPHITRON_OCCURRENCE_BINDING_TENANT;
        var r = GRAPHITRON_OCCURRENCE_BINDING_TENANT_RULE;
        dsl.insertInto(b)
            .columns(b.GRAPH_NAME, b.COORDINATE, b.BOUND, b.KIND, b.TOUCHED_AT)
            .select(dsl.select(r.GRAPH_NAME, r.COORDINATE, r.BOUND, r.KIND, val(touchedAt, b.TOUCHED_AT))
                .from(r)
                .where(r.GRAPH_NAME.eq(graph)))
            .onConflict(b.GRAPH_NAME, b.COORDINATE, b.BOUND, b.KIND)
            .doUpdate()
            .set(b.TOUCHED_AT, excluded(b.TOUCHED_AT))
            .execute();
        dsl.insertInto(t)
            .columns(t.GRAPH_NAME, t.COORDINATE, t.CALL_COORDINATE, t.CALL, t.KIND, t.ORIGIN, t.KEY_POSITION,
                t.TOUCHED_AT)
            .select(dsl.select(r.GRAPH_NAME, r.COORDINATE, r.CALL_COORDINATE, r.CALL, r.KIND, r.ORIGIN,
                    r.KEY_POSITION, val(touchedAt, t.TOUCHED_AT))
                .from(r)
                .where(r.GRAPH_NAME.eq(graph)))
            // The binding's own key, every column of it inserted: the primary key's bound is generated.
            .onConflict(t.GRAPH_NAME, t.COORDINATE, t.CALL_COORDINATE, t.CALL)
            .doUpdate()
            .set(t.ORIGIN, excluded(t.ORIGIN))
            .set(t.KEY_POSITION, excluded(t.KEY_POSITION))
            .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
            .execute();
    }

    /**
     * The slots {@code rule} states, as supertype rows and as {@code subtype} rows carrying what each
     * rests on: the subtype's columns beyond the supertype's key and kind, each named {@code COLUMN}
     * or {@code RULE_COLUMN:SUBTYPE_COLUMN} where the two spell it differently.
     */
    private static void slots(DSLContext dsl, String graph, LocalDateTime touchedAt, Table<?> rule,
                              Table<?> subtype, List<String> carried) {
        var s = GRAPHITRON_OCCURRENCE_TENANT_SLOT;
        Field<String> ruleGraph = rule.field("GRAPH_NAME", String.class);
        Field<String> ruleCoordinate = rule.field("COORDINATE", String.class);
        var presence = Presence.of(ruleGraph, ruleCoordinate);
        dsl.insertInto(s)
            .columns(s.GRAPH_NAME, s.CALL_COORDINATE, s.CALL, s.SLOT, s.KIND, s.COORDINATE, s.KEY_POSITION,
                s.GUARANTEED, s.PER_ELEMENT, s.OWN_ARGUMENT, s.TOUCHED_AT)
            .select(dsl.select(ruleGraph, rule.field("CALL_COORDINATE", String.class),
                    rule.field("CALL", String.class), rule.field("SLOT", String.class),
                    rule.field("KIND", String.class), ruleCoordinate,
                    rule.field("KEY_POSITION", Integer.class), presence.guaranteed(), presence.perElement(),
                    presence.ownArgument(), val(touchedAt, s.TOUCHED_AT))
                .from(rule)
                .where(ruleGraph.eq(graph)))
            .onConflict(s.GRAPH_NAME, s.CALL_COORDINATE, s.CALL, s.SLOT)
            .doUpdate()
            .set(s.KIND, excluded(s.KIND))
            .set(s.COORDINATE, excluded(s.COORDINATE))
            .set(s.KEY_POSITION, excluded(s.KEY_POSITION))
            .set(s.GUARANTEED, excluded(s.GUARANTEED))
            .set(s.PER_ELEMENT, excluded(s.PER_ELEMENT))
            .set(s.OWN_ARGUMENT, excluded(s.OWN_ARGUMENT))
            .set(s.TOUCHED_AT, excluded(s.TOUCHED_AT))
            .execute();
        var columns = new java.util.ArrayList<Field<?>>();
        var selected = new java.util.ArrayList<Field<?>>();
        for (String name : List.of("GRAPH_NAME", "CALL_COORDINATE", "CALL", "SLOT", "KIND", "COORDINATE")) {
            columns.add(subtype.field(name));
            selected.add(rule.field(name));
        }
        for (String pair : carried) {
            String from = pair.contains(":") ? pair.substring(0, pair.indexOf(':')) : pair;
            String to = pair.contains(":") ? pair.substring(pair.indexOf(':') + 1) : pair;
            columns.add(subtype.field(to));
            selected.add(rule.field(from));
        }
        Field<LocalDateTime> touched = subtype.field("TOUCHED_AT", LocalDateTime.class);
        columns.add(touched);
        selected.add(val(touchedAt, touched));
        var updates = new java.util.LinkedHashMap<Field<?>, Field<?>>();
        for (Field<?> column : columns.subList(4, columns.size())) {
            updates.put(column, excluded(column));
        }
        dsl.insertInto(subtype).columns(columns)
            .select(dsl.select(selected).from(rule).where(ruleGraph.eq(graph)))
            .onConflict(subtype.field("GRAPH_NAME"), subtype.field("CALL_COORDINATE"), subtype.field("CALL"),
                subtype.field("SLOT"))
            .doUpdate()
            .set(updates)
            .execute();
    }

    /**
     * How a slot's value is present, which is a fact of its occurrence and the same for every slot the
     * occurrence is: whether every call holds it once, or once per element of a list, the occurrence
     * being no list of its own or read under one, and whether every list it is read through is its own
     * argument. An occurrence that may be left out is neither, read off the occurrences' own relations.
     */
    private record Presence(Field<Boolean> guaranteed, Field<Boolean> perElement, Field<Boolean> ownArgument) {

        static Presence of(Field<String> graph, Field<String> coordinate) {
            var op = GRAPHITRON_OCCURRENCE_OPTIONAL;
            var le = GRAPHITRON_OCCURRENCE_LIST_ELEMENT;
            var it = GRAPHITRON_OCCURRENCE_INPUT_TYPE;
            var o = GRAPHITRON_OCCURRENCE;
            var optional = exists(selectOne().from(op).where(op.GRAPH_NAME.eq(graph), op.COORDINATE.eq(coordinate)));
            var underList = exists(selectOne().from(le).where(le.GRAPH_NAME.eq(graph), le.COORDINATE.eq(coordinate)));
            var isList = coalesce(field(select(it.IS_LIST).from(it)
                .where(it.GRAPH_NAME.eq(graph), it.COORDINATE.eq(coordinate))), inline(false)).isTrue();
            var inputFieldList = exists(selectOne().from(o).join(it)
                .on(it.GRAPH_NAME.eq(o.GRAPH_NAME), it.COORDINATE.eq(o.COORDINATE))
                .where(o.GRAPH_NAME.eq(graph), o.COORDINATE.eq(coordinate), o.KIND.eq("INPUT_FIELD"),
                    it.IS_LIST.isTrue()));
            var otherList = exists(selectOne().from(le).join(o)
                .on(o.GRAPH_NAME.eq(le.GRAPH_NAME), o.COORDINATE.eq(le.COORDINATE))
                .where(le.GRAPH_NAME.eq(graph), le.COORDINATE.eq(coordinate),
                    le.LIST_COORDINATE.ne(o.ARGUMENT_COORDINATE)));
            return new Presence(
                field(not(optional).and(not(underList)).and(not(isList))),
                field(not(optional).and(underList.or(isList))),
                field(not(otherList).and(not(inputFieldList))));
        }
    }
}
