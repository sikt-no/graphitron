package no.sikt.graphitron.model.derive;

import no.sikt.graphitron.model.tables.GraphitronResult;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;

import java.time.LocalDateTime;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_RESULT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_RESULT_CLASS;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_RESULT_PIVOT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_RESULT_RULE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_RESULT_TABLE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_TYPE_BACKING;
import static org.jooq.impl.DSL.exists;
import static org.jooq.impl.DSL.inline;
import static org.jooq.impl.DSL.selectOne;
import static org.jooq.impl.DSL.val;

/**
 * Fills {@code graphitron_result} and its subtypes: the closure of a result flowing down from the
 * producer that made it, through the readers that hand it on, marked and swept.
 *
 * <p>{@code graphitron_result_rule} is one round of it, and this merges the round until it changes
 * nothing. A view cannot state the fixpoint, and H2 evaluates a recursive view again for every row
 * joined to it and does not deduplicate. The rule steps only from rows marked with the reading's own
 * instant, {@code store_graph.last_captured}, so a result only an earlier reading reached hands nothing
 * on. A row this reading reaches again keeps its key and only takes the new mark, so a reference into
 * it holds across captures; the sweep at the end removes what this reading did not reach.
 */
public final class Results {

    private Results() {}

    /** Far more rounds than any nesting is deep; reaching it means the rule stopped converging. */
    private static final int ROUND_LIMIT = 1000;

    public static void derive(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        for (int round = 0; round(dsl, graph, touchedAt) > 0; round++) {
            if (round == ROUND_LIMIT) {
                throw new IllegalStateException("graphitron_result did not converge for " + graph
                    + " in " + ROUND_LIMIT + " rounds");
            }
        }
        classes(dsl, graph, touchedAt);
        sweep(dsl, graph, touchedAt);
    }

    /**
     * Merges one round of the rule, the supertype before the subtypes keyed into it, and answers how
     * many rows it added or marked. A row this reading already marked changes nothing, which is what
     * lets the count reach zero.
     */
    private static int round(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        var result = GRAPHITRON_RESULT;
        var table = GRAPHITRON_RESULT_TABLE;
        var pivot = GRAPHITRON_RESULT_PIVOT;
        var rule = GRAPHITRON_RESULT_RULE;

        var supertypes = dsl.selectDistinct(rule.GRAPH_NAME, rule.TYPE_NAME, rule.SHAPE)
            .from(rule).where(rule.GRAPH_NAME.eq(graph)).asTable("s");
        var mark = val(touchedAt, result.TOUCHED_AT);
        int changed = dsl.mergeInto(result)
            .using(supertypes)
            .on(result.GRAPH_NAME.eq(supertypes.field(rule.GRAPH_NAME))
                .and(result.TYPE_NAME.eq(supertypes.field(rule.TYPE_NAME)))
                .and(result.SHAPE.eq(supertypes.field(rule.SHAPE))))
            .whenMatchedAnd(result.TOUCHED_AT.ne(mark)).thenUpdate().set(result.TOUCHED_AT, mark)
            .whenNotMatchedThenInsert(result.GRAPH_NAME, result.TYPE_NAME, result.SHAPE, result.TOUCHED_AT)
            .values(supertypes.field(rule.GRAPH_NAME), supertypes.field(rule.TYPE_NAME),
                supertypes.field(rule.SHAPE), mark)
            .execute();

        var tables = dsl.selectDistinct(rule.GRAPH_NAME, rule.TYPE_NAME, rule.SHAPE,
                rule.TABLE_SOURCE_NAME, rule.TABLE_SCHEMA, rule.TABLE_NAME)
            .from(rule).where(rule.GRAPH_NAME.eq(graph))
            .and(rule.TABLE_NAME.isNotNull())
            .and(supertypeMarked(result, rule.GRAPH_NAME, rule.TYPE_NAME, rule.SHAPE, touchedAt))
            .asTable("t");
        var tableMark = val(touchedAt, table.TOUCHED_AT);
        changed += dsl.mergeInto(table)
            .using(tables)
            .on(table.GRAPH_NAME.eq(tables.field(rule.GRAPH_NAME))
                .and(table.TYPE_NAME.eq(tables.field(rule.TYPE_NAME)))
                .and(table.TABLE_SOURCE_NAME.eq(tables.field(rule.TABLE_SOURCE_NAME)))
                .and(table.TABLE_SCHEMA.eq(tables.field(rule.TABLE_SCHEMA)))
                .and(table.TABLE_NAME.eq(tables.field(rule.TABLE_NAME))))
            .whenMatchedAnd(table.TOUCHED_AT.ne(tableMark)).thenUpdate().set(table.TOUCHED_AT, tableMark)
            .whenNotMatchedThenInsert(table.GRAPH_NAME, table.TYPE_NAME, table.SHAPE,
                table.TABLE_SOURCE_NAME, table.TABLE_SCHEMA, table.TABLE_NAME, table.TOUCHED_AT)
            .values(tables.field(rule.GRAPH_NAME), tables.field(rule.TYPE_NAME),
                tables.field(rule.SHAPE), tables.field(rule.TABLE_SOURCE_NAME),
                tables.field(rule.TABLE_SCHEMA), tables.field(rule.TABLE_NAME), tableMark)
            .execute();

        var pivots = dsl.selectDistinct(rule.GRAPH_NAME, rule.TYPE_NAME, rule.SHAPE,
                rule.PIVOT_TYPE_NAME, rule.PIVOT_FIELD_NAME)
            .from(rule).where(rule.GRAPH_NAME.eq(graph))
            .and(rule.PIVOT_FIELD_NAME.isNotNull())
            .and(supertypeMarked(result, rule.GRAPH_NAME, rule.TYPE_NAME, rule.SHAPE, touchedAt))
            .asTable("p");
        var pivotMark = val(touchedAt, pivot.TOUCHED_AT);
        changed += dsl.mergeInto(pivot)
            .using(pivots)
            .on(pivot.GRAPH_NAME.eq(pivots.field(rule.GRAPH_NAME))
                .and(pivot.TYPE_NAME.eq(pivots.field(rule.TYPE_NAME)))
                .and(pivot.PIVOT_TYPE_NAME.eq(pivots.field(rule.PIVOT_TYPE_NAME)))
                .and(pivot.PIVOT_FIELD_NAME.eq(pivots.field(rule.PIVOT_FIELD_NAME))))
            .whenMatchedAnd(pivot.TOUCHED_AT.ne(pivotMark)).thenUpdate().set(pivot.TOUCHED_AT, pivotMark)
            .whenNotMatchedThenInsert(pivot.GRAPH_NAME, pivot.TYPE_NAME, pivot.SHAPE,
                pivot.PIVOT_TYPE_NAME, pivot.PIVOT_FIELD_NAME, pivot.TOUCHED_AT)
            .values(pivots.field(rule.GRAPH_NAME), pivots.field(rule.TYPE_NAME),
                pivots.field(rule.SHAPE), pivots.field(rule.PIVOT_TYPE_NAME),
                pivots.field(rule.PIVOT_FIELD_NAME), pivotMark)
            .execute();
        return changed;
    }

    /** The class an object result is an instance of, where a class backs the type. */
    private static void classes(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        var result = GRAPHITRON_RESULT;
        var cls = GRAPHITRON_RESULT_CLASS;
        var backing = GRAPHITRON_TYPE_BACKING;
        var source = dsl.selectDistinct(result.GRAPH_NAME, result.TYPE_NAME, result.SHAPE,
                backing.CLASS_NAME)
            .from(result)
            .join(backing)
            .on(backing.GRAPH_NAME.eq(result.GRAPH_NAME))
            .and(backing.TYPE_NAME.eq(result.TYPE_NAME))
            .where(result.GRAPH_NAME.eq(graph))
            .and(result.TOUCHED_AT.eq(touchedAt))
            .and(result.SHAPE.eq(inline("OBJECT")))
            .and(backing.DECLARED_VIA.in("PRODUCER", "ACCESSOR"))
            .asTable("c");
        var mark = val(touchedAt, cls.TOUCHED_AT);
        dsl.mergeInto(cls)
            .using(source)
            .on(cls.GRAPH_NAME.eq(source.field(result.GRAPH_NAME))
                .and(cls.TYPE_NAME.eq(source.field(result.TYPE_NAME)))
                .and(cls.CLASS_NAME.eq(source.field(backing.CLASS_NAME))))
            .whenMatchedThenUpdate().set(cls.TOUCHED_AT, mark)
            .whenNotMatchedThenInsert(cls.GRAPH_NAME, cls.TYPE_NAME, cls.SHAPE, cls.CLASS_NAME,
                cls.TOUCHED_AT)
            .values(source.field(result.GRAPH_NAME), source.field(result.TYPE_NAME),
                source.field(result.SHAPE), source.field(backing.CLASS_NAME), mark)
            .execute();
    }

    /** The rows this reading did not reach, the subtypes before the results they key into. */
    private static void sweep(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        var table = GRAPHITRON_RESULT_TABLE;
        dsl.deleteFrom(table).where(table.GRAPH_NAME.eq(graph)).and(table.TOUCHED_AT.ne(touchedAt))
            .execute();
        var pivot = GRAPHITRON_RESULT_PIVOT;
        dsl.deleteFrom(pivot).where(pivot.GRAPH_NAME.eq(graph)).and(pivot.TOUCHED_AT.ne(touchedAt))
            .execute();
        var cls = GRAPHITRON_RESULT_CLASS;
        dsl.deleteFrom(cls).where(cls.GRAPH_NAME.eq(graph)).and(cls.TOUCHED_AT.ne(touchedAt))
            .execute();
        var result = GRAPHITRON_RESULT;
        dsl.deleteFrom(result).where(result.GRAPH_NAME.eq(graph)).and(result.TOUCHED_AT.ne(touchedAt))
            .execute();
    }

    /**
     * The supertype row a subtype row keys into, marked by this reading. Each statement of a round
     * evaluates the rule again, so a subtype merge sees a step from rows the supertype merge has just
     * marked; those wait for the next round, which marks their supertype first.
     */
    private static Condition supertypeMarked(GraphitronResult result, Field<String> graph,
            Field<String> type, Field<String> shape, LocalDateTime touchedAt) {
        return exists(selectOne().from(result)
            .where(result.GRAPH_NAME.eq(graph))
            .and(result.TYPE_NAME.eq(type))
            .and(result.SHAPE.eq(shape))
            .and(result.TOUCHED_AT.eq(touchedAt)));
    }
}
