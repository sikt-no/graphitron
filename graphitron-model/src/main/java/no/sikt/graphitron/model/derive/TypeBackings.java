package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;

import java.time.LocalDateTime;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_PAYLOAD_DATA_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_PAYLOAD_DATA_FIELD_RULE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_TYPE_BACKING;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_TYPE_BACKING_ACCESSOR_RULE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_TYPE_BACKING_RULE;
import static org.jooq.impl.DSL.excluded;
import static org.jooq.impl.DSL.val;

/**
 * The writer of {@code graphitron_payload_data_field} and {@code graphitron_type_backing}: the fields
 * a payload's producer hands its values down to, and which Java class stands for each of a graph's
 * types, and through which population.
 *
 * <p>The payload's data fields come first, from their own rule view: the backing rule's passthrough
 * reads them, as the producer rule and the producer result rule of later stages do. Every input
 * they are read from, the claims, the field targets and the table bindings, is written by an
 * earlier stage.
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
 * between two statements sees it missing. The payload's data fields are marked and swept the same way.
 */
public final class TypeBackings {

    private TypeBackings() {}

    /**
     * Marks every payload data field and every backing the reading finds, each relation then swept of
     * the graph's rows it did not.
     */
    public static void derive(DSLContext dsl, String graphName, LocalDateTime touchedAt) {
        dataFields(dsl, graphName, touchedAt);

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

    /** Marks the graph's payload data fields the reading finds, then sweeps the ones it did not. */
    private static void dataFields(DSLContext dsl, String graphName, LocalDateTime touchedAt) {
        var target = GRAPHITRON_PAYLOAD_DATA_FIELD;
        var rule = GRAPHITRON_PAYLOAD_DATA_FIELD_RULE;
        dsl.insertInto(target)
            .columns(target.GRAPH_NAME, target.TYPE_NAME, target.FIELD_NAME, target.KIND, target.TOUCHED_AT)
            .select(dsl
                .select(rule.GRAPH_NAME, rule.TYPE_NAME, rule.FIELD_NAME, rule.KIND,
                    val(touchedAt, target.TOUCHED_AT))
                .from(rule)
                .where(rule.GRAPH_NAME.eq(graphName)))
            .onConflict(target.GRAPH_NAME, target.TYPE_NAME, target.FIELD_NAME)
            .doUpdate()
            .set(target.KIND, excluded(target.KIND))
            .set(target.TOUCHED_AT, excluded(target.TOUCHED_AT))
            .execute();
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
