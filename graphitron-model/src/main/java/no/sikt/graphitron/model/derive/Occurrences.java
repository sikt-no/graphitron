package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;

import java.time.LocalDateTime;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_INPUT_TYPE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_INPUT_TYPE_RULE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_START_RULE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_STEP_RULE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_CHAIN;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_CHAIN_RULE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_TYPE;
import static org.jooq.impl.DSL.excluded;
import static org.jooq.impl.DSL.selectDistinct;
import static org.jooq.impl.DSL.selectOne;
import static org.jooq.impl.DSL.val;

/**
 * Fills {@code graphitron_occurrence}: every place a field or an argument is reached, at its nested
 * coordinate, marked and swept; then {@code graphitron_occurrence_input_type}, the type each
 * argument and input field occurrence takes.
 *
 * <p>{@code graphitron_occurrence_start_rule} names where chains start, and
 * {@code graphitron_occurrence_step_rule} one step down from an occurrence this reading wrote. This
 * applies the step a depth at a time, as the first-visit guard needs: a step does not enter a type
 * already on its chain, the occurrence that would close a cycle keeping its row and growing none.
 * Each depth's chain, {@code graphitron_occurrence_chain}, is written from its rule before the next
 * step reads it, so the guard is the step rule's own and no recursive query is asked of H2, which
 * states one badly over a graph that can cycle. Each depth is one statement per relation, the
 * instant and the depth bound as parameters, so its plans are the same across passes and captures.
 */
public final class Occurrences {

    private Occurrences() {}

    public static void derive(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        starts(dsl, graph, touchedAt);
        chain(dsl, graph, touchedAt, 0);
        int bound = 1 + dsl.fetchCount(GRAPHITRON_TYPE, GRAPHITRON_TYPE.GRAPH_NAME.eq(graph));
        for (int depth = 0; step(dsl, graph, touchedAt, depth) > 0; depth++) {
            if (depth > bound) {
                throw new IllegalStateException("graphitron_occurrence did not converge for " + graph
                    + " in " + bound + " depths; the first-visit guard has stopped being effective");
            }
            chain(dsl, graph, touchedAt, depth + 1);
        }
        dsl.deleteFrom(GRAPHITRON_OCCURRENCE)
            .where(GRAPHITRON_OCCURRENCE.GRAPH_NAME.eq(graph))
            .and(GRAPHITRON_OCCURRENCE.TOUCHED_AT.ne(touchedAt))
            .execute();
        dsl.deleteFrom(GRAPHITRON_OCCURRENCE_CHAIN)
            .where(GRAPHITRON_OCCURRENCE_CHAIN.GRAPH_NAME.eq(graph))
            .and(GRAPHITRON_OCCURRENCE_CHAIN.TOUCHED_AT.ne(touchedAt))
            .execute();
        inputTypes(dsl, graph, touchedAt);
    }

    /**
     * The chains of this reading's occurrences at {@code depth}, from the rule over the depth above's;
     * a type an older reading put on one of them goes before the next step reads it.
     */
    private static void chain(DSLContext dsl, String graph, LocalDateTime touchedAt, int depth) {
        var o = GRAPHITRON_OCCURRENCE;
        var ch = GRAPHITRON_OCCURRENCE_CHAIN;
        var r = GRAPHITRON_OCCURRENCE_CHAIN_RULE;
        dsl.insertInto(ch)
            .columns(ch.GRAPH_NAME, ch.COORDINATE, ch.TYPE_NAME, ch.TOUCHED_AT)
            .select(dsl.select(r.GRAPH_NAME, r.COORDINATE, r.TYPE_NAME, val(touchedAt, ch.TOUCHED_AT))
                .from(r)
                .join(o).on(o.GRAPH_NAME.eq(r.GRAPH_NAME), o.COORDINATE.eq(r.COORDINATE))
                .where(r.GRAPH_NAME.eq(graph))
                .and(o.DEPTH.eq(val(depth)))
                .and(o.TOUCHED_AT.eq(val(touchedAt, o.TOUCHED_AT))))
            .onConflict(ch.GRAPH_NAME, ch.COORDINATE, ch.TYPE_NAME)
            .doUpdate()
            .set(ch.TOUCHED_AT, excluded(ch.TOUCHED_AT))
            .execute();
        dsl.deleteFrom(ch)
            .where(ch.GRAPH_NAME.eq(graph))
            .and(ch.TOUCHED_AT.ne(touchedAt))
            .andExists(selectOne().from(o)
                .where(o.GRAPH_NAME.eq(ch.GRAPH_NAME), o.COORDINATE.eq(ch.COORDINATE), o.DEPTH.eq(val(depth)),
                    o.TOUCHED_AT.eq(val(touchedAt, o.TOUCHED_AT))))
            .execute();
    }

    /** The type each argument and input field occurrence this reading kept takes, marked and swept. */
    private static void inputTypes(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        var t = GRAPHITRON_OCCURRENCE_INPUT_TYPE;
        var r = GRAPHITRON_OCCURRENCE_INPUT_TYPE_RULE;
        dsl.insertInto(t)
            .columns(t.GRAPH_NAME, t.COORDINATE, t.TYPE_NAME, t.TYPE_KIND, t.IS_LIST, t.JAVA_CLASS, t.TOUCHED_AT)
            .select(dsl.select(r.GRAPH_NAME, r.COORDINATE, r.TYPE_NAME, r.TYPE_KIND, r.IS_LIST, r.JAVA_CLASS,
                    val(touchedAt, t.TOUCHED_AT))
                .from(r)
                .where(r.GRAPH_NAME.eq(graph)))
            .onDuplicateKeyUpdate()
            .set(t.TYPE_NAME, excluded(t.TYPE_NAME))
            .set(t.TYPE_KIND, excluded(t.TYPE_KIND))
            .set(t.IS_LIST, excluded(t.IS_LIST))
            .set(t.JAVA_CLASS, excluded(t.JAVA_CLASS))
            .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
            .execute();
        dsl.deleteFrom(t)
            .where(t.GRAPH_NAME.eq(graph))
            .and(t.TOUCHED_AT.ne(touchedAt))
            .execute();
    }

    /** The chains' starts, at depth 0. */
    private static void starts(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        var o = GRAPHITRON_OCCURRENCE;
        var s = GRAPHITRON_OCCURRENCE_START_RULE;
        var source = selectDistinct(s.GRAPH_NAME, s.COORDINATE, s.ELEMENT_COORDINATE, s.NAME, s.KIND)
            .from(s)
            .where(s.GRAPH_NAME.eq(graph))
            .asTable("s");
        merge(dsl, source.field(s.GRAPH_NAME), source, 0, touchedAt);
    }

    /** One depth: the step rule's children of this reading's occurrences at {@code depth}. */
    private static int step(DSLContext dsl, String graph, LocalDateTime touchedAt, int depth) {
        var r = GRAPHITRON_OCCURRENCE_STEP_RULE;
        var source = dsl.select(r.GRAPH_NAME, r.COORDINATE, r.ELEMENT_COORDINATE, r.PARENT_COORDINATE, r.NAME,
                r.KIND)
            .from(r)
            .where(r.GRAPH_NAME.eq(graph))
            .and(r.PARENT_DEPTH.eq(val(depth)))
            .asTable("r");
        return merge(dsl, source.field(r.GRAPH_NAME), source, depth + 1, touchedAt);
    }

    /** Marks the rows already there and inserts the rest, answering how many it touched. */
    private static int merge(DSLContext dsl, org.jooq.Field<String> graphField,
                             org.jooq.Table<?> source, int depth, LocalDateTime touchedAt) {
        var o = GRAPHITRON_OCCURRENCE;
        var coordinate = source.field(o.COORDINATE.getName(), String.class);
        var element = source.field(o.ELEMENT_COORDINATE.getName(), String.class);
        var name = source.field(o.NAME.getName(), String.class);
        var kind = source.field(o.KIND.getName(), String.class);
        var mark = val(touchedAt, o.TOUCHED_AT);
        var at = val(depth, o.DEPTH);
        return dsl.mergeInto(o)
            .using(source)
            .on(o.GRAPH_NAME.eq(graphField).and(o.COORDINATE.eq(coordinate)))
            .whenMatchedAnd(o.TOUCHED_AT.ne(mark)).thenUpdate()
            .set(o.ELEMENT_COORDINATE, element)
            .set(o.NAME, name)
            .set(o.KIND, kind)
            .set(o.DEPTH, at)
            .set(o.TOUCHED_AT, mark)
            .set(o.ELEMENT_GRAPH_REF, graphField)
            .set(o.ELEMENT_COORDINATE_REF, element)
            .whenNotMatchedThenInsert(o.GRAPH_NAME, o.COORDINATE, o.ELEMENT_COORDINATE, o.NAME,
                o.KIND, o.DEPTH, o.TOUCHED_AT, o.ELEMENT_GRAPH_REF, o.ELEMENT_COORDINATE_REF)
            .values(graphField, coordinate, element, name, kind, at, mark, graphField, element)
            .execute();
    }
}
