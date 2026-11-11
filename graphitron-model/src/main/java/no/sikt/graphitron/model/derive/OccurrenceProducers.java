package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Table;

import java.time.LocalDateTime;
import java.util.List;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_PRODUCER;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_PRODUCER_RESULT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_PRODUCER_RESULT_RULE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_PRODUCER_ROUTINE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_PRODUCER_ROUTINE_RULE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_PRODUCER_RULE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_CALL;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_CALL_CANDIDATE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_CALL_CANDIDATE_RULE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_CALL_METHOD;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_CALL_METHOD_RULE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_CALL_RULE;
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
 * beside each, marking what this reading states; and {@code graphitron_occurrence_call} for each
 * producer's call, the supertype the call is a subtype of too.
 *
 * <p>The first step of the assembly's per-depth loop, before {@link OccurrenceCallParameters} fills the
 * depth's calls and {@link OccurrenceSources} states the depth's sources, which read the producers
 * of their depth and what they produce. The rules read today's field-keyed
 * producer relations through each occurrence's declared field, so nothing they read is written in
 * the loop; reading the parent's source is what the per-depth placement is for. {@link #sweep} runs
 * once after the loop.
 */
public final class OccurrenceProducers {

    private OccurrenceProducers() {}

    /**
     * States the producers of the occurrences at {@code depth}, then their starts, their calls'
     * candidates and the calls and methods they pin; {@link #writeResults} states what they produce
     * once the calls are filled.
     */
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
        // The calls' candidates, stored before the pin and the fills read them; a candidate this
        // reading no longer states goes now, or the pin would weigh it.
        var k = GRAPHITRON_OCCURRENCE_CALL_CANDIDATE;
        var kr = GRAPHITRON_OCCURRENCE_CALL_CANDIDATE_RULE;
        dsl.deleteFrom(k)
            .where(k.GRAPH_NAME.eq(graph), k.CALL_KIND.eq("SERVICE"))
            .and(atDepth(k.GRAPH_NAME, k.COORDINATE, depth))
            .andNotExists(selectOne().from(kr)
                .where(kr.GRAPH_NAME.eq(k.GRAPH_NAME), kr.COORDINATE.eq(k.COORDINATE), kr.CALL.eq(k.CALL),
                    kr.METHOD_SOURCE_NAME.eq(k.METHOD_SOURCE_NAME), kr.METHOD_CLASS_NAME.eq(k.METHOD_CLASS_NAME),
                    kr.METHOD_NAME.eq(k.METHOD_NAME), kr.METHOD_DESCRIPTOR.eq(k.METHOD_DESCRIPTOR)))
            .execute();
        refresh(dsl, k, kr, graph, depth, touchedAt);
        // The calls and the methods they pin, before the subtypes that are calls as well as producers;
        // a service's only, a condition's call waiting on the depth's bindings.
        refresh(dsl, GRAPHITRON_OCCURRENCE_CALL, GRAPHITRON_OCCURRENCE_CALL_RULE, graph, depth, touchedAt,
            GRAPHITRON_OCCURRENCE_CALL_RULE.CALL_KIND.eq("SERVICE"));
        refresh(dsl, GRAPHITRON_OCCURRENCE_CALL_METHOD, GRAPHITRON_OCCURRENCE_CALL_METHOD_RULE, graph, depth,
            touchedAt, GRAPHITRON_OCCURRENCE_CALL_METHOD_RULE.CALL_KIND.eq("SERVICE"));
        refresh(dsl, GRAPHITRON_OCCURRENCE_PRODUCER_SERVICE, GRAPHITRON_OCCURRENCE_PRODUCER_SERVICE_RULE,
            graph, depth, touchedAt);
        refresh(dsl, GRAPHITRON_OCCURRENCE_PRODUCER_ROUTINE, GRAPHITRON_OCCURRENCE_PRODUCER_ROUTINE_RULE,
            graph, depth, touchedAt);
        refresh(dsl, GRAPHITRON_OCCURRENCE_PRODUCER_WRITE, GRAPHITRON_OCCURRENCE_PRODUCER_WRITE_RULE,
            graph, depth, touchedAt);
    }

    /**
     * States what the producers at {@code depth} produce, after their calls are pinned and filled:
     * a service's result reads the method its call pinned.
     */
    public static void writeResults(DSLContext dsl, String graph, int depth, LocalDateTime touchedAt) {
        refresh(dsl, GRAPHITRON_OCCURRENCE_PRODUCER_RESULT, GRAPHITRON_OCCURRENCE_PRODUCER_RESULT_RULE,
            graph, depth, touchedAt);
    }

    /** Deletes the rows no depth of this reading marked, subtypes before the producer they hang on. */
    public static void sweep(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        for (Table<?> t : List.of(GRAPHITRON_OCCURRENCE_PRODUCER_SERVICE,
                GRAPHITRON_OCCURRENCE_PRODUCER_ROUTINE, GRAPHITRON_OCCURRENCE_PRODUCER_WRITE,
                GRAPHITRON_OCCURRENCE_PRODUCER_RESULT, GRAPHITRON_OCCURRENCE_CALL_METHOD, GRAPHITRON_OCCURRENCE_PRODUCER,
                GRAPHITRON_OCCURRENCE_CALL, GRAPHITRON_OCCURRENCE_CALL_CANDIDATE)) {
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
        refresh(dsl, target, rule, graph, depth, touchedAt, org.jooq.impl.DSL.noCondition());
    }

    /** {@link #refresh} of the rule's rows that {@code only} admits. */
    static void refresh(DSLContext dsl, Table<?> target, Table<?> rule, String graph, int depth,
                        LocalDateTime touchedAt, org.jooq.Condition only) {
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
                .and(atDepth(ruleGraph, ruleCoordinate, depth))
                .and(only))
            .onConflict(key)
            .doUpdate()
            .set(updates)
            .execute();
    }

    /** Whether the occurrence at {@code coordinate} sits at {@code depth}. */
    static org.jooq.Condition atDepth(Field<String> graph, Field<String> coordinate, int depth) {
        var o = GRAPHITRON_OCCURRENCE;
        return org.jooq.impl.DSL.exists(selectOne().from(o)
            .where(o.GRAPH_NAME.eq(graph), o.COORDINATE.eq(coordinate), o.DEPTH.eq(depth)));
    }
}
