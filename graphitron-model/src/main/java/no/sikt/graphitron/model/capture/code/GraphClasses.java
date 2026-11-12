package no.sikt.graphitron.model.capture.code;

import org.jooq.DSLContext;

import java.time.LocalDateTime;

import static no.sikt.graphitron.model.Tables.CODE_GRAPH_CLASS;
import static no.sikt.graphitron.model.Tables.CODE_GRAPH_CLASS_RULE;
import static org.jooq.impl.DSL.excluded;
import static org.jooq.impl.DSL.val;

/**
 * The writer of {@code code_graph_class}: the class each name on a graph's classpath resolves to,
 * the copy on the first of the graph's entries declaring it.
 *
 * <p>The code gatherer's anchor stage. Its reading writes every entry's classes keyed by the entry,
 * whatever else was read beside it, so a name two entries declare is two classes; which one the
 * graph sees is a question about the graph, answered here once from the graph's entries and their
 * positions, which the gatherers this one depends on have already written.
 *
 * <p>Run on every capture of the graph and never skipped because no entry changed: the order is the
 * graph's, and a winning entry leaving hands its names to the next entry declaring them, which no
 * reading of the remaining entries would notice. Marked and swept: every row the rule computes is
 * written or touched with the reading's instant, the declaring entry updated where it moved, and the
 * graph's rows carrying any other instant are names no entry declares any longer.
 */
public final class GraphClasses {

    private GraphClasses() {}

    /** Marks every name the graph's classpath declares, then sweeps the graph's names it did not. */
    public static void write(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        var target = CODE_GRAPH_CLASS;
        var rule = CODE_GRAPH_CLASS_RULE;
        dsl.insertInto(target)
            .columns(target.GRAPH_NAME, target.CLASS_NAME, target.SOURCE_NAME, target.TOUCHED_AT)
            .select(dsl
                .select(rule.GRAPH_NAME, rule.CLASS_NAME, rule.SOURCE_NAME,
                    val(touchedAt, target.TOUCHED_AT))
                .from(rule)
                .where(rule.GRAPH_NAME.eq(graph)))
            .onDuplicateKeyUpdate()
            .set(target.SOURCE_NAME, excluded(target.SOURCE_NAME))
            .set(target.TOUCHED_AT, excluded(target.TOUCHED_AT))
            .execute();
        dsl.deleteFrom(target)
            .where(target.GRAPH_NAME.eq(graph))
            .and(target.TOUCHED_AT.ne(touchedAt))
            .execute();
    }
}
