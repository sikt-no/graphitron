package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Table;

import java.time.LocalDateTime;
import java.util.List;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_PRODUCER;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_PRODUCER_KEY_ROW;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_PRODUCER_KEY_ROW_RULE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_PRODUCER_RESULT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_PRODUCER_RESULT_RULE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_PRODUCER_ROUTINE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_PRODUCER_ROUTINE_RULE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_PRODUCER_RULE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_PRODUCER_SERVICE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_PRODUCER_SERVICE_RULE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_PRODUCER_WRITE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_PRODUCER_WRITE_RULE;
import static org.jooq.impl.DSL.excluded;
import static org.jooq.impl.DSL.selectOne;
import static org.jooq.impl.DSL.val;

/**
 * Writes {@code graphitron_occurrence_producer} and its subtypes, the start, the call and the result: which
 * occurrences execute something and produce a result, one depth at a time, from the rule view
 * beside each, marking what this reading states.
 *
 * <p>A step of {@link OccurrenceSources}' depth loop, called before a depth's sources, which read the
 * producers of their depth. The rules read today's field-keyed producer relations through each
 * occurrence's declared field, so nothing they read is written in the loop; reading the parent's
 * source is what the per-depth placement is for. {@link #sweep} runs once after the loop.
 */
public final class OccurrenceProducers {

    private OccurrenceProducers() {}

    /** States the producers of the occurrences at {@code depth}, then their starts and calls. */
    public static void writeDepth(DSLContext dsl, String graph, int depth, LocalDateTime touchedAt) {
        var p = GRAPHITRON_OCCURRENCE_PRODUCER;
        var r = GRAPHITRON_OCCURRENCE_PRODUCER_RULE;
        // A producer whose kind changed keeps its key, and a subtype row naming the old kind would
        // hold the update back; it is no longer true, so it goes, its subtypes with it.
        dsl.deleteFrom(p)
            .where(p.GRAPH_NAME.eq(graph))
            .and(atDepth(p.GRAPH_NAME, p.COORDINATE, depth))
            .andNotExists(selectOne().from(r)
                .where(r.GRAPH_NAME.eq(p.GRAPH_NAME), r.COORDINATE.eq(p.COORDINATE), r.KIND.eq(p.KIND)))
            .execute();
        refresh(dsl, p, r, graph, depth, touchedAt);
        refresh(dsl, GRAPHITRON_OCCURRENCE_PRODUCER_KEY_ROW, GRAPHITRON_OCCURRENCE_PRODUCER_KEY_ROW_RULE,
            graph, depth, touchedAt);
        refresh(dsl, GRAPHITRON_OCCURRENCE_PRODUCER_SERVICE, GRAPHITRON_OCCURRENCE_PRODUCER_SERVICE_RULE,
            graph, depth, touchedAt);
        refresh(dsl, GRAPHITRON_OCCURRENCE_PRODUCER_ROUTINE, GRAPHITRON_OCCURRENCE_PRODUCER_ROUTINE_RULE,
            graph, depth, touchedAt);
        refresh(dsl, GRAPHITRON_OCCURRENCE_PRODUCER_WRITE, GRAPHITRON_OCCURRENCE_PRODUCER_WRITE_RULE,
            graph, depth, touchedAt);
        // What it produces, after the call, which a service's result reads.
        refresh(dsl, GRAPHITRON_OCCURRENCE_PRODUCER_RESULT, GRAPHITRON_OCCURRENCE_PRODUCER_RESULT_RULE,
            graph, depth, touchedAt);
    }

    /** Deletes the rows no depth of this reading marked, subtypes before the producer they hang on. */
    public static void sweep(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        for (Table<?> t : List.of(GRAPHITRON_OCCURRENCE_PRODUCER_KEY_ROW, GRAPHITRON_OCCURRENCE_PRODUCER_SERVICE,
                GRAPHITRON_OCCURRENCE_PRODUCER_ROUTINE, GRAPHITRON_OCCURRENCE_PRODUCER_WRITE,
                GRAPHITRON_OCCURRENCE_PRODUCER_RESULT, GRAPHITRON_OCCURRENCE_PRODUCER)) {
            dsl.deleteFrom(t)
                .where(t.field("GRAPH_NAME", String.class).eq(graph))
                .and(t.field("TOUCHED_AT", LocalDateTime.class).ne(touchedAt))
                .execute();
        }
    }

    /**
     * Upserts {@code target}'s rows at {@code depth} from {@code rule}, whose columns are the
     * target's own but for the instant, matched by name.
     */
    private static void refresh(DSLContext dsl, Table<?> target, Table<?> rule, String graph, int depth,
                                LocalDateTime touchedAt) {
        var key = target.getPrimaryKey().getFields();
        var columns = new java.util.ArrayList<Field<?>>();
        var selected = new java.util.ArrayList<Field<?>>();
        var updates = new java.util.LinkedHashMap<Field<?>, Field<?>>();
        Field<LocalDateTime> touched = target.field("TOUCHED_AT", LocalDateTime.class);
        for (Field<?> column : target.fields()) {
            if (column.getName().equals(touched.getName())) {
                continue;
            }
            columns.add(column);
            selected.add(rule.field(column.getName()));
            if (!key.contains(column)) {
                updates.put(column, excluded(column));
            }
        }
        columns.add(touched);
        selected.add(val(touchedAt, touched));
        updates.put(touched, excluded(touched));
        var ruleGraph = rule.field("GRAPH_NAME", String.class);
        var ruleCoordinate = rule.field("COORDINATE", String.class);
        dsl.insertInto(target).columns(columns)
            .select(dsl.select(selected).from(rule)
                .where(ruleGraph.eq(graph))
                .and(atDepth(ruleGraph, ruleCoordinate, depth)))
            .onConflict(key)
            .doUpdate()
            .set(updates)
            .execute();
    }

    /** Whether the occurrence at {@code coordinate} sits at {@code depth}. */
    private static org.jooq.Condition atDepth(Field<String> graph, Field<String> coordinate, int depth) {
        var o = GRAPHITRON_OCCURRENCE;
        return org.jooq.impl.DSL.exists(selectOne().from(o)
            .where(o.GRAPH_NAME.eq(graph), o.COORDINATE.eq(coordinate), o.DEPTH.eq(depth)));
    }
}
