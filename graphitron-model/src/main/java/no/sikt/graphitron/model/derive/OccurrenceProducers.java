package no.sikt.graphitron.model.derive;

import no.sikt.graphitron.model.catalog.GeneratedColumns;
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
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_WRITE_CALL_RULE;
import static org.jooq.impl.DSL.excluded;
import static org.jooq.impl.DSL.selectOne;
import static org.jooq.impl.DSL.val;

/**
 * Writes {@code graphitron_occurrence_producer} and its subtypes, the start, the call and the result: which
 * occurrences execute something and produce a result, from the rule view beside each, marking what
 * this reading states; and {@code graphitron_occurrence_call} for each producer's call, the supertype
 * the call is a subtype of too.
 *
 * <p>The {@code occurrence-producers} stage, ahead of {@link OccurrenceDepths}, whose sources read
 * the producers, what they produce and the service calls' fills. The rules read the field-keyed
 * producer relations through each occurrence's declared field and nothing the depths write, so the
 * whole graph is written at once. Of the call relations this stage owns the service calls' rows; the
 * condition calls' are {@link OccurrenceBindings}'.
 */
public final class OccurrenceProducers {

    private OccurrenceProducers() {}

    /** The stage: the producers and their service and write calls, the calls' fills, what they produce, then the sweep. */
    public static void derive(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        write(dsl, graph, touchedAt);
        OccurrenceCallParameters.writeServices(dsl, graph, touchedAt);
        OccurrenceCallParameters.sweep(dsl, graph, touchedAt, "SERVICE");
        OccurrenceCallParameters.writeWrites(dsl, graph, touchedAt);
        OccurrenceCallParameters.sweep(dsl, graph, touchedAt, "WRITE");
        writeResults(dsl, graph, touchedAt);
        sweep(dsl, graph, touchedAt);
    }

    /**
     * States the producers, then their starts, their calls' candidates and the calls and methods they
     * pin; {@link #writeResults} states what they produce once the calls are filled.
     */
    private static void write(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        var p = GRAPHITRON_OCCURRENCE_PRODUCER;
        var r = GRAPHITRON_OCCURRENCE_PRODUCER_RULE;
        // A producer whose kind changed keeps its key, and a subtype row naming the old kind would
        // hold the update back; it is no longer true, so it goes, its subtypes with it.
        dsl.deleteFrom(p)
            .where(p.GRAPH_NAME.eq(graph))
            .andNotExists(selectOne().from(r)
                .where(r.GRAPH_NAME.eq(p.GRAPH_NAME), r.COORDINATE.eq(p.COORDINATE), r.KIND.eq(p.KIND)))
            .execute();
        refresh(dsl, p, r, graph, null, touchedAt);
        // The calls' candidates, stored before the pin and the fills read them; a candidate this
        // reading no longer states goes now, or the pin would weigh it.
        var k = GRAPHITRON_OCCURRENCE_CALL_CANDIDATE;
        var kr = GRAPHITRON_OCCURRENCE_CALL_CANDIDATE_RULE;
        dsl.deleteFrom(k)
            .where(k.GRAPH_NAME.eq(graph), k.CALL_KIND.eq("SERVICE"))
            .andNotExists(selectOne().from(kr)
                .where(kr.GRAPH_NAME.eq(k.GRAPH_NAME), kr.COORDINATE.eq(k.COORDINATE), kr.CALL.eq(k.CALL),
                    kr.METHOD_SOURCE_NAME.eq(k.METHOD_SOURCE_NAME), kr.METHOD_CLASS_NAME.eq(k.METHOD_CLASS_NAME),
                    kr.METHOD_NAME.eq(k.METHOD_NAME), kr.METHOD_DESCRIPTOR.eq(k.METHOD_DESCRIPTOR)))
            .execute();
        refresh(dsl, k, kr, graph, null, touchedAt);
        // The calls and the methods they pin, before the subtypes that are calls as well as producers;
        // a service's only, a condition's call waiting on the bindings.
        refresh(dsl, GRAPHITRON_OCCURRENCE_CALL, GRAPHITRON_OCCURRENCE_CALL_RULE, graph, null, touchedAt,
            GRAPHITRON_OCCURRENCE_CALL_RULE.CALL_KIND.eq("SERVICE"));
        refresh(dsl, GRAPHITRON_OCCURRENCE_CALL_METHOD, GRAPHITRON_OCCURRENCE_CALL_METHOD_RULE, graph, null,
            touchedAt, GRAPHITRON_OCCURRENCE_CALL_METHOD_RULE.CALL_KIND.eq("SERVICE"));
        // Every service call is stated by now, so one this reading did not mark is stale; it goes
        // before the fills and results read the method it pinned last time.
        sweepCalls(dsl, graph, touchedAt, "SERVICE");
        refresh(dsl, GRAPHITRON_OCCURRENCE_PRODUCER_SERVICE, GRAPHITRON_OCCURRENCE_PRODUCER_SERVICE_RULE,
            graph, null, touchedAt);
        refresh(dsl, GRAPHITRON_OCCURRENCE_PRODUCER_ROUTINE, GRAPHITRON_OCCURRENCE_PRODUCER_ROUTINE_RULE,
            graph, null, touchedAt);
        refresh(dsl, GRAPHITRON_OCCURRENCE_PRODUCER_WRITE, GRAPHITRON_OCCURRENCE_PRODUCER_WRITE_RULE,
            graph, null, touchedAt);
        // A write's call, read off the write it makes, since it pins no method.
        refresh(dsl, GRAPHITRON_OCCURRENCE_CALL, GRAPHITRON_OCCURRENCE_WRITE_CALL_RULE, graph, null, touchedAt);
        sweepCalls(dsl, graph, touchedAt, "WRITE");
    }

    /**
     * States what the producers produce, after their calls are pinned and filled: a service's result
     * reads the method its call pinned.
     */
    private static void writeResults(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        refresh(dsl, GRAPHITRON_OCCURRENCE_PRODUCER_RESULT, GRAPHITRON_OCCURRENCE_PRODUCER_RESULT_RULE,
            graph, null, touchedAt);
    }

    /**
     * Deletes the producer rows this reading did not mark, subtypes before the producer they hang on;
     * the service calls went as soon as they were all stated.
     */
    private static void sweep(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        for (Table<?> t : List.of(GRAPHITRON_OCCURRENCE_PRODUCER_SERVICE,
                GRAPHITRON_OCCURRENCE_PRODUCER_ROUTINE, GRAPHITRON_OCCURRENCE_PRODUCER_WRITE,
                GRAPHITRON_OCCURRENCE_PRODUCER_RESULT, GRAPHITRON_OCCURRENCE_PRODUCER)) {
            dsl.deleteFrom(t)
                .where(t.field("GRAPH_NAME", String.class).eq(graph))
                .and(t.field("TOUCHED_AT", LocalDateTime.class).ne(touchedAt))
                .execute();
        }
    }

    /**
     * Deletes the calls of {@code callKind} this reading did not mark, the method before the call it
     * pins; each stage owning a kind of call sweeps its own.
     */
    static void sweepCalls(DSLContext dsl, String graph, LocalDateTime touchedAt, String callKind) {
        for (Table<?> t : List.of(GRAPHITRON_OCCURRENCE_CALL_METHOD, GRAPHITRON_OCCURRENCE_CALL,
                GRAPHITRON_OCCURRENCE_CALL_CANDIDATE)) {
            dsl.deleteFrom(t)
                .where(t.field("GRAPH_NAME", String.class).eq(graph))
                .and(t.field("CALL_KIND", String.class).eq(callKind))
                .and(t.field("TOUCHED_AT", LocalDateTime.class).ne(touchedAt))
                .execute();
        }
    }

    /**
     * Upserts {@code target}'s rows at {@code depth}, or the whole graph's where it is null, from
     * {@code rule}, whose columns are the target's own but for the instant, matched by name. A column
     * the database generates is left out, the store being what says which those are
     * ({@link GeneratedColumns}).
     */
    private static void refresh(DSLContext dsl, Table<?> target, Table<?> rule, String graph, Integer depth,
                                LocalDateTime touchedAt) {
        refresh(dsl, target, rule, graph, depth, touchedAt, org.jooq.impl.DSL.noCondition());
    }

    /** {@link #refresh} of the rule's rows that {@code only} admits. */
    static void refresh(DSLContext dsl, Table<?> target, Table<?> rule, String graph, Integer depth,
                        LocalDateTime touchedAt, org.jooq.Condition only) {
        var key = target.getPrimaryKey().getFields();
        var columns = new java.util.ArrayList<Field<?>>();
        var selected = new java.util.ArrayList<Field<?>>();
        var updates = new java.util.LinkedHashMap<Field<?>, Field<?>>();
        Field<LocalDateTime> touched = target.field("TOUCHED_AT", LocalDateTime.class);
        var generated = GeneratedColumns.of(dsl, target);
        for (Field<?> column : target.fields()) {
            if (column.getName().equals(touched.getName()) || generated.contains(column)) {
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

    /** Whether the occurrence at {@code coordinate} sits at {@code depth}; any, where it is null. */
    static org.jooq.Condition atDepth(Field<String> graph, Field<String> coordinate, Integer depth) {
        if (depth == null) {
            return org.jooq.impl.DSL.noCondition();
        }
        var o = GRAPHITRON_OCCURRENCE;
        return org.jooq.impl.DSL.exists(selectOne().from(o)
            .where(o.GRAPH_NAME.eq(graph), o.COORDINATE.eq(coordinate), o.DEPTH.eq(depth)));
    }
}
