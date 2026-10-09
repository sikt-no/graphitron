package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;

import java.time.LocalDateTime;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_TYPE_BACKING;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_TYPE_BACKING_RULE;
import static org.jooq.impl.DSL.excluded;
import static org.jooq.impl.DSL.val;

/**
 * The writer of {@code graphitron_type_backing}: which Java class stands for each of a graph's
 * types, and through which population.
 *
 * <p>One statement over the rule view beside the target, run by the graphitron gatherer after
 * {@link ResolvedTypeBindings}, whose table the bound-table arm reads. The closure over members that
 * used to need a producer of its own is a recursive statement here: the classpath is read before
 * this gatherer runs, so every edge it walks is a fact already stored.
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

        dsl.deleteFrom(target)
            .where(target.GRAPH_NAME.eq(graphName))
            .and(target.TOUCHED_AT.ne(touchedAt))
            .execute();
    }
}
