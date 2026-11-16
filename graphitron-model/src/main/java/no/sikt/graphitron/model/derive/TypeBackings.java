package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;

import java.time.LocalDateTime;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_TYPE_BACKING;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_TYPE_BACKING_ACCESSOR_RULE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_TYPE_BACKING_RULE;
import static org.jooq.impl.DSL.excluded;
import static org.jooq.impl.DSL.val;

/**
 * The writer of {@code graphitron_type_backing}: which Java class stands for each of a graph's
 * types, and through which population.
 *
 * <p>Run by the graphitron gatherer after the anchor writes {@code graphitron_tabletype}, which the
 * bound-table rows read. The table bindings and what producers ground come from the rule view beside
 * the target; the accessor closure over members comes from a second rule taken one step at a time,
 * from the backings this reading has reached, until a step reaches nothing new. A recursive view would
 * state the closure in one statement, but H2 compiles one for about a second in every session and a
 * capture evicts it from the session's cache of compiled views, so every capture paid that second.
 * The classpath is read before this gatherer runs, so every edge a step takes is a fact already
 * stored.
 *
 * <p>Marked and swept rather than cleared and rewritten: every backing the reading finds is written
 * or touched with the reading's instant, and the graph's backings carrying any other instant are the
 * ones the reading no longer found. A backing that stays true is never deleted, so nothing reading it
 * between two statements sees it missing.
 */
public final class TypeBackings {

    private TypeBackings() {}

    /** Marks every backing the reading finds, then sweeps the graph's backings it did not. */
    public static void derive(DSLContext dsl, String graphName, LocalDateTime touchedAt) {
        var target = GRAPHITRON_TYPE_BACKING;
        var rule = GRAPHITRON_TYPE_BACKING_RULE;

        dsl.insertInto(target)
            .columns(target.GRAPH_NAME, target.TYPE_NAME, target.CLASS_NAME, target.DECLARED_VIA,
                target.TOUCHED_AT)
            .select(dsl
                .select(rule.GRAPH_NAME, rule.TYPE_NAME, rule.CLASS_NAME, rule.DECLARED_VIA,
                    val(touchedAt, target.TOUCHED_AT))
                .from(rule)
                .where(rule.GRAPH_NAME.eq(graphName)))
            .onDuplicateKeyUpdate()
            .set(target.TOUCHED_AT, excluded(target.TOUCHED_AT))
            .execute();

        // The closure, a step a round, from this reading's backings only: a backing the last reading
        // left seeds nothing before the sweep takes it.
        var step = GRAPHITRON_TYPE_BACKING_ACCESSOR_RULE;
        int reached = marked(dsl, graphName, touchedAt);
        while (true) {
            dsl.insertInto(target)
                .columns(target.GRAPH_NAME, target.TYPE_NAME, target.CLASS_NAME, target.DECLARED_VIA,
                    target.TOUCHED_AT)
                .select(dsl
                    .selectDistinct(step.GRAPH_NAME, step.TYPE_NAME, step.CLASS_NAME, step.DECLARED_VIA,
                        val(touchedAt, target.TOUCHED_AT))
                    .from(step)
                    .where(step.GRAPH_NAME.eq(graphName), step.REACHED_TOUCHED_AT.eq(touchedAt)))
                .onDuplicateKeyUpdate()
                .set(target.TOUCHED_AT, excluded(target.TOUCHED_AT))
                .execute();
            int now = marked(dsl, graphName, touchedAt);
            if (now == reached) {
                break;
            }
            reached = now;
        }

        dsl.deleteFrom(target)
            .where(target.GRAPH_NAME.eq(graphName))
            .and(target.TOUCHED_AT.ne(touchedAt))
            .execute();
    }

    /** How many of the graph's backings this reading has marked so far. */
    private static int marked(DSLContext dsl, String graphName, LocalDateTime touchedAt) {
        var target = GRAPHITRON_TYPE_BACKING;
        return dsl.fetchCount(target, target.GRAPH_NAME.eq(graphName).and(target.TOUCHED_AT.eq(touchedAt)));
    }
}
