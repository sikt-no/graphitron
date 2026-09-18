package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_RESOLVED_TYPE_BINDING;
import static no.sikt.graphitron.model.Tables.INTENT_BOUND_TABLE;
import static no.sikt.graphitron.model.Tables.INTENT_ROUTINE_RETURN_BINDING;
import static org.jooq.impl.DSL.count;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.partitionBy;
import static org.jooq.impl.SQLDataType.INTEGER;

/**
 * The capture-cadence writer of {@code graphitron_resolved_type_binding}: which catalog table
 * stands for a graph's type, from either population that can answer.
 *
 * <p>Two arms and one relation, because what a reader of a binding asks is which table stands for
 * the type and never which rule found it: the author's {@code @table} binding, and the binding a
 * {@code @routine} chain's return derives from where the chain lands. The two populations stay
 * separate relations of their own, each deriving by its own rule from its own facts, and this is
 * where they meet.
 *
 * <p>The union dedupes, which is what makes the type and the table triple a key: a type both arms
 * answer for with the same table is one row, and the count beside it is the arity over the
 * partition rather than a fact about either arm.
 *
 * <p>Runs as a stage of the graphitron gatherer after the hops the routine arm's chain walks and
 * before the walk that seeds from these rows.
 */
public final class ResolvedTypeBindings {

    private ResolvedTypeBindings() {}

    /** Reconciles the graph's type bindings: clears the partition, then re-derives it. */
    public static void derive(DSLContext dsl, String graphName) {
        var t = GRAPHITRON_RESOLVED_TYPE_BINDING;
        var authored = INTENT_BOUND_TABLE;
        var returned = INTENT_ROUTINE_RETURN_BINDING;

        dsl.deleteFrom(t).where(t.GRAPH_NAME.eq(graphName)).execute();

        var bound = dsl
            .select(authored.GRAPH_NAME, authored.TYPE_NAME, authored.TABLE_SOURCE_NAME,
                authored.TABLE_SCHEMA, authored.TABLE_NAME)
            .from(authored)
            .where(authored.GRAPH_NAME.eq(graphName))
            .union(dsl
                .select(returned.GRAPH_NAME, returned.TYPE_NAME, returned.TABLE_SOURCE_NAME,
                    returned.TABLE_SCHEMA, returned.TABLE_NAME)
                .from(returned)
                .where(returned.GRAPH_NAME.eq(graphName)))
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
