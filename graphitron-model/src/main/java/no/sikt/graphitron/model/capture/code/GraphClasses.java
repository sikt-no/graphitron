package no.sikt.graphitron.model.capture.code;

import org.jooq.DSLContext;
import org.jooq.Field;

import java.time.LocalDateTime;
import java.util.List;

import static no.sikt.graphitron.model.Tables.CODE_CLASS_DECLARED_SUPERTYPE;
import static no.sikt.graphitron.model.Tables.CODE_GRAPH_CLASS;
import static no.sikt.graphitron.model.Tables.CODE_GRAPH_CLASS_RULE;
import static org.jooq.impl.DSL.any;
import static org.jooq.impl.DSL.excluded;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.inline;
import static org.jooq.impl.DSL.min;
import static org.jooq.impl.DSL.select;
import static org.jooq.impl.DSL.val;
import static org.jooq.impl.DSL.when;

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
 *
 * <p>Then whether each class is a jOOQ record, which is a question about the graph for the same
 * reason: the chain above a class runs through whichever copy of each supertype name the graph
 * sees. Decided over the edges the reading stated, {@code code_class_declared_supertype}, and never
 * by reading a class the reading did not read.
 */
public final class GraphClasses {

    private GraphClasses() {}

    /** The type every jOOQ record is, and the anchor the reachability grows down from. */
    private static final String JOOQ_RECORD = "org.jooq.Record";

    /**
     * The package only the platform may define a class in, which is why a name in it needs no row
     * to be complete: no classpath entry can declare one, and the platform depends on no jOOQ type.
     */
    private static final String PLATFORM_ONLY = "java.%";

    /** Far more rounds than any hierarchy is deep; reaching it means a round stopped converging. */
    private static final int ROUND_LIMIT = 1000;

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
        jooqRecords(dsl, graph);
    }

    /**
     * Whether each of the graph's classes is a jOOQ record: TRUE where its chain reaches
     * {@code org.jooq.Record}, NULL with the least unread name where it reaches a name the graph
     * reads no class of, FALSE otherwise.
     *
     * <p>Two anchored reachabilities, each grown downward one hop per round over the graph's own
     * edges until a round changes nothing, and never an all-pairs closure: the classes reaching the
     * record, then, among the rest, the classes reaching a name the graph does not read. Each round
     * reaches only the classes naming what the round before settled, through the edges by supertype
     * name, so the work follows the records and the unread chains rather than the graph's classes.
     * A round only ever sets a flag that was unset or lowers a name, so it terminates on any edge
     * set, a cycle a broken classpath could hold included; the round limit is the guard should that
     * ever fail. Recomputed whole on every capture, as the names it reads are.
     */
    private static void jooqRecords(DSLContext dsl, String graph) {
        var c = CODE_GRAPH_CLASS;
        var p = CODE_GRAPH_CLASS.as("p");
        var e = CODE_CLASS_DECLARED_SUPERTYPE;
        dsl.update(c)
            .set(c.IS_JOOQ_RECORD, (Boolean) null)
            .set(c.UNREAD_SUPERTYPE_NAME, (String) null)
            .where(c.GRAPH_NAME.eq(graph))
            .execute();
        dsl.update(c)
            .set(c.IS_JOOQ_RECORD, inline(true))
            .where(c.GRAPH_NAME.eq(graph))
            .and(c.CLASS_NAME.eq(JOOQ_RECORD))
            .execute();
        // A class naming a record, or naming a class this graph already knows to be one, grown from
        // the names a round settled to the classes naming them and no further. The name is the anchor
        // rather than its row, so a class naming org.jooq.Record is one even where the graph reads no
        // jOOQ jar.
        var frontier = List.of(JOOQ_RECORD);
        for (int rounds = 0; !frontier.isEmpty(); rounds++) {
            guard("is_jooq_record", rounds);
            var reached = namingAny(dsl, graph, frontier, c.IS_JOOQ_RECORD.isNull());
            if (!reached.isEmpty()) {
                dsl.update(c)
                    .set(c.IS_JOOQ_RECORD, inline(true))
                    .where(c.GRAPH_NAME.eq(graph))
                    .and(c.CLASS_NAME.eq(any(val(reached.toArray(String[]::new)))))
                    .execute();
            }
            frontier = reached;
        }
        // Among the rest, the least name the chain reaches that the graph reads no class of: a
        // supertype with no row here is that name itself, one with a row hands on what it reached.
        Field<String> least = field(select(min(
                when(p.CLASS_NAME.isNull(), e.SUPERTYPE_NAME).otherwise(p.UNREAD_SUPERTYPE_NAME)))
            .from(e)
            .leftJoin(p).on(p.GRAPH_NAME.eq(c.GRAPH_NAME)).and(p.CLASS_NAME.eq(e.SUPERTYPE_NAME))
            .where(e.SOURCE_NAME.eq(c.SOURCE_NAME))
            .and(e.CLASS_NAME.eq(c.CLASS_NAME))
            .and(e.SUPERTYPE_NAME.notLike(PLATFORM_ONLY)));
        var lowerable = c.IS_JOOQ_RECORD.isNull().and(least.isNotNull())
            .and(c.UNREAD_SUPERTYPE_NAME.isNull().or(least.lt(c.UNREAD_SUPERTYPE_NAME)));
        // One pass over the graph's classes settles every class naming an unread name itself; after
        // it a class's name can only lower where a supertype's did, so each later round reaches only
        // the classes naming one the round before touched, and stops when none lowers.
        dsl.update(c)
            .set(c.UNREAD_SUPERTYPE_NAME, least)
            .where(c.GRAPH_NAME.eq(graph))
            .and(lowerable)
            .execute();
        List<String> touched = dsl.select(c.CLASS_NAME).from(c)
            .where(c.GRAPH_NAME.eq(graph))
            .and(c.UNREAD_SUPERTYPE_NAME.isNotNull())
            .fetch(c.CLASS_NAME);
        for (int rounds = 0; !touched.isEmpty(); rounds++) {
            guard("unread_supertype_name", rounds);
            var reached = namingAny(dsl, graph, touched, c.IS_JOOQ_RECORD.isNull());
            int lowered = reached.isEmpty() ? 0 : dsl.update(c)
                .set(c.UNREAD_SUPERTYPE_NAME, least)
                .where(c.GRAPH_NAME.eq(graph))
                .and(c.CLASS_NAME.eq(any(val(reached.toArray(String[]::new)))))
                .and(lowerable)
                .execute();
            touched = lowered == 0 ? List.of() : reached;
        }
        dsl.update(c)
            .set(c.IS_JOOQ_RECORD, inline(false))
            .where(c.GRAPH_NAME.eq(graph))
            .and(c.IS_JOOQ_RECORD.isNull())
            .and(c.UNREAD_SUPERTYPE_NAME.isNull())
            .execute();
    }

    /**
     * The graph's classes meeting {@code condition} whose own classfile names one of {@code names}
     * as a supertype: read from the edges by supertype name, so a round costs what its frontier
     * reaches rather than a pass over the graph's classes. The names bind as one array, so every
     * round runs one statement.
     */
    private static List<String> namingAny(DSLContext dsl, String graph, List<String> names,
                                          org.jooq.Condition condition) {
        var c = CODE_GRAPH_CLASS;
        var e = CODE_CLASS_DECLARED_SUPERTYPE;
        return dsl.selectDistinct(c.CLASS_NAME).from(e)
            .join(c).on(c.GRAPH_NAME.eq(graph)).and(c.SOURCE_NAME.eq(e.SOURCE_NAME))
                .and(c.CLASS_NAME.eq(e.CLASS_NAME))
            .where(e.SUPERTYPE_NAME.eq(any(val(names.toArray(String[]::new)))))
            .and(condition)
            .fetch(c.CLASS_NAME);
    }

    /** Fails a reachability that has run more rounds than any hierarchy is deep. */
    private static void guard(String column, int rounds) {
        if (rounds == ROUND_LIMIT) {
            throw new IllegalStateException("code_graph_class." + column + " did not converge in "
                + ROUND_LIMIT + " rounds");
        }
    }
}
