package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_RESOLVED_TYPE_BINDING;
import static no.sikt.graphitron.model.Tables.INTENT_BOUND_TABLE;
import static org.jooq.impl.DSL.count;
import static org.jooq.impl.DSL.partitionBy;
import static org.jooq.impl.SQLDataType.INTEGER;

/**
 * The capture-cadence writer of {@code graphitron_resolved_type_binding}: which catalog table
 * stands for a graph's type.
 *
 * <p>The author's {@code @table} is the one source. A {@code @routine} binds nothing: a field whose
 * path ends on one hands its type rows of the function's result, which is {@code graphitron_result}'s
 * to say, and a {@code @reference} whose return type has no {@code @table} has nowhere to arrive,
 * which is a defect at the {@code @reference}.
 *
 * <p>The count beside each row is the arity over the type, a type two {@code @table} spellings
 * resolve differently for being ambiguous rather than decided here.
 *
 * <p>Runs as a stage of the graphitron gatherer before the walk that seeds from these rows.
 */
public final class ResolvedTypeBindings {

    private ResolvedTypeBindings() {}

    /** Reconciles the graph's type bindings: clears the partition, then re-derives it. */
    public static void derive(DSLContext dsl, String graphName) {
        var t = GRAPHITRON_RESOLVED_TYPE_BINDING;
        var authored = INTENT_BOUND_TABLE;

        dsl.deleteFrom(t).where(t.GRAPH_NAME.eq(graphName)).execute();

        // Distinct first and counted after, so a table spelled twice for one type is one row.
        var bound = dsl
            .selectDistinct(authored.GRAPH_NAME, authored.TYPE_NAME, authored.TABLE_SOURCE_NAME,
                authored.TABLE_SCHEMA, authored.TABLE_NAME)
            .from(authored)
            .where(authored.GRAPH_NAME.eq(graphName))
            .asTable("bound");

        dsl.insertInto(t, t.GRAPH_NAME, t.TYPE_NAME, t.TABLE_SOURCE_NAME, t.TABLE_SCHEMA,
                t.TABLE_NAME, t.CANDIDATES)
            .select(dsl
                .select(bound.field(authored.GRAPH_NAME), bound.field(authored.TYPE_NAME),
                    bound.field(authored.TABLE_SOURCE_NAME), bound.field(authored.TABLE_SCHEMA),
                    bound.field(authored.TABLE_NAME),
                    count().over(partitionBy(bound.field(authored.GRAPH_NAME),
                        bound.field(authored.TYPE_NAME))).cast(INTEGER))
                .from(bound))
            .execute();
    }
}
