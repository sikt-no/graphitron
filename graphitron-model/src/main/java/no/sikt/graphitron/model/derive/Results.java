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
import static org.jooq.impl.DSL.notExists;
import static org.jooq.impl.DSL.selectOne;
import static org.jooq.impl.DSL.val;

/**
 * Fills {@code graphitron_result} and its subtypes: the closure of a result flowing down from the
 * producer that made it, through the readers that hand it on.
 *
 * <p>{@code graphitron_result_rule} is one round of it, the seeds and one step from what these
 * relations already hold, and this inserts its new rows until a round adds none. A view cannot state
 * the fixpoint, and H2 evaluates a recursive view again for every row joined to it and does not
 * deduplicate. A round reads every row these relations hold, so the graph's rows are cleared first:
 * a row only the previous reading reached would otherwise seed this one. Nothing outside the four
 * relations keys into them, so the clear cascades nowhere else.
 */
public final class Results {

    private Results() {}

    /** Far more rounds than any nesting is deep; reaching it means the rule stopped converging. */
    private static final int ROUND_LIMIT = 1000;

    /**
     * The supertype row a subtype row keys into. Each statement of a round evaluates the rule again,
     * so the subtype inserts see a step from rows the supertype insert has just added; those wait for
     * the next round, which adds their supertype first.
     */
    private static Condition supertypeHolds(GraphitronResult result,
            Field<String> graph, Field<String> type, Field<String> shape) {
        return exists(selectOne().from(result)
            .where(result.GRAPH_NAME.eq(graph))
            .and(result.TYPE_NAME.eq(type))
            .and(result.SHAPE.eq(shape)));
    }

    public static void derive(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        var result = GRAPHITRON_RESULT;
        var table = GRAPHITRON_RESULT_TABLE;
        var pivot = GRAPHITRON_RESULT_PIVOT;
        var rule = GRAPHITRON_RESULT_RULE;

        dsl.deleteFrom(result).where(result.GRAPH_NAME.eq(graph)).execute();

        for (int round = 0; ; round++) {
            if (round == ROUND_LIMIT) {
                throw new IllegalStateException("graphitron_result did not converge for " + graph
                    + " in " + ROUND_LIMIT + " rounds");
            }
            int added = dsl.insertInto(result)
                .columns(result.GRAPH_NAME, result.TYPE_NAME, result.SHAPE, result.TOUCHED_AT)
                .select(dsl.selectDistinct(rule.GRAPH_NAME, rule.TYPE_NAME, rule.SHAPE,
                        val(touchedAt, result.TOUCHED_AT))
                    .from(rule)
                    .where(rule.GRAPH_NAME.eq(graph))
                    .and(notExists(selectOne().from(result)
                        .where(result.GRAPH_NAME.eq(rule.GRAPH_NAME))
                        .and(result.TYPE_NAME.eq(rule.TYPE_NAME))
                        .and(result.SHAPE.eq(rule.SHAPE)))))
                .execute();
            added += dsl.insertInto(table)
                .columns(table.GRAPH_NAME, table.TYPE_NAME, table.SHAPE, table.TABLE_SOURCE_NAME,
                    table.TABLE_SCHEMA, table.TABLE_NAME, table.TOUCHED_AT)
                .select(dsl.selectDistinct(rule.GRAPH_NAME, rule.TYPE_NAME, rule.SHAPE,
                        rule.TABLE_SOURCE_NAME, rule.TABLE_SCHEMA, rule.TABLE_NAME,
                        val(touchedAt, table.TOUCHED_AT))
                    .from(rule)
                    .where(rule.GRAPH_NAME.eq(graph))
                    .and(rule.TABLE_NAME.isNotNull())
                    .and(supertypeHolds(result, rule.GRAPH_NAME, rule.TYPE_NAME, rule.SHAPE))
                    .and(notExists(selectOne().from(table)
                        .where(table.GRAPH_NAME.eq(rule.GRAPH_NAME))
                        .and(table.TYPE_NAME.eq(rule.TYPE_NAME))
                        .and(table.TABLE_SOURCE_NAME.eq(rule.TABLE_SOURCE_NAME))
                        .and(table.TABLE_SCHEMA.eq(rule.TABLE_SCHEMA))
                        .and(table.TABLE_NAME.eq(rule.TABLE_NAME)))))
                .execute();
            added += dsl.insertInto(pivot)
                .columns(pivot.GRAPH_NAME, pivot.TYPE_NAME, pivot.SHAPE, pivot.PIVOT_TYPE_NAME,
                    pivot.PIVOT_FIELD_NAME, pivot.TOUCHED_AT)
                .select(dsl.selectDistinct(rule.GRAPH_NAME, rule.TYPE_NAME, rule.SHAPE,
                        rule.PIVOT_TYPE_NAME, rule.PIVOT_FIELD_NAME, val(touchedAt, pivot.TOUCHED_AT))
                    .from(rule)
                    .where(rule.GRAPH_NAME.eq(graph))
                    .and(rule.PIVOT_FIELD_NAME.isNotNull())
                    .and(supertypeHolds(result, rule.GRAPH_NAME, rule.TYPE_NAME, rule.SHAPE))
                    .and(notExists(selectOne().from(pivot)
                        .where(pivot.GRAPH_NAME.eq(rule.GRAPH_NAME))
                        .and(pivot.TYPE_NAME.eq(rule.TYPE_NAME))
                        .and(pivot.PIVOT_TYPE_NAME.eq(rule.PIVOT_TYPE_NAME))
                        .and(pivot.PIVOT_FIELD_NAME.eq(rule.PIVOT_FIELD_NAME)))))
                .execute();
            if (added == 0) {
                break;
            }
        }

        // The class an object result is an instance of, where a class backs the type.
        var cls = GRAPHITRON_RESULT_CLASS;
        var backing = GRAPHITRON_TYPE_BACKING;
        dsl.insertInto(cls)
            .columns(cls.GRAPH_NAME, cls.TYPE_NAME, cls.SHAPE, cls.CLASS_NAME, cls.TOUCHED_AT)
            .select(dsl.selectDistinct(result.GRAPH_NAME, result.TYPE_NAME, result.SHAPE,
                    backing.CLASS_NAME, val(touchedAt, cls.TOUCHED_AT))
                .from(result)
                .join(backing)
                .on(backing.GRAPH_NAME.eq(result.GRAPH_NAME))
                .and(backing.TYPE_NAME.eq(result.TYPE_NAME))
                .where(result.GRAPH_NAME.eq(graph))
                .and(result.SHAPE.eq(inline("OBJECT")))
                .and(backing.DECLARED_VIA.in("PRODUCER", "ACCESSOR")))
            .execute();
    }
}
