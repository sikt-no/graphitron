package no.sikt.graphitron.model.derive;

import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record10;
import org.jooq.Select;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_NAVIGATION;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ROUTINE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_TABLETYPE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_POLY_MEMBER;
import static no.sikt.graphitron.model.Tables.SQL_TABLE;
import static no.sikt.graphitron.model.Tables.STORE_GRAPH_SOURCE;
import static org.jooq.impl.DSL.count;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.max;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.notExists;
import static org.jooq.impl.DSL.partitionBy;
import static org.jooq.impl.DSL.select;
import static org.jooq.impl.DSL.selectOne;
import static org.jooq.impl.DSL.val;

/**
 * The capture-cadence writer of {@code graphitron_field}: where a field's rows come from, and where
 * the field departs from to reach them.
 *
 * <p>Called as a stage of the graphitron gatherer after the navigation it stands on, and everything
 * else it reads has flushed by then: the table bindings are that gatherer's own earlier stage, the
 * polymorphic membership is the SDL crawler's, and the routine carries its own spelling, which this
 * resolves where it was written. No rule here reads a derivation.
 *
 * <p>The departure is the enclosing type's binding; the arrival is whichever of the three rules
 * below answered. That is why one is nullable and the other is not: a root field has no enclosing
 * row to depart from, so its three departure columns are null together and that is the fact rather
 * than a gap, while every row a field returns still comes from somewhere.
 *
 * <p>Three rules name a target and they are disjoint by construction rather than ranked. The
 * navigated type binds a table, or it is a polymorphic container binding none of its own and each
 * table-bound participant is a target, or the field's chain ends on a {@code @routine} whose result
 * binds the return. The second and third each demand that the first did not answer, so no window
 * function is needed and a reader never has to know which rule to prefer.
 *
 * <p>A field with several targets is several rows. That is the point of keying on the target: a
 * multi-table polymorphic container is one statement per participant, and two participants backed by
 * one table are one target rather than two, which is why the participant arm is distinct on the
 * table it arrives at rather than on the member it came from.
 */
public final class FieldEndpoints {

    private FieldEndpoints() {}

    /** Clears and re-derives the graph's field endpoints; see the class javadoc. */
    public static void derive(DSLContext dsl, String graphName) {
        // Cleared first so the call is idempotent: capture makes it once per graph, and a caller
        // re-deriving in order to read the result makes it as often as it likes.
        dsl.deleteFrom(GRAPHITRON_FIELD)
            .where(GRAPHITRON_FIELD.GRAPH_NAME.eq(graphName)).execute();
        insert(dsl, namedType(dsl, graphName));
        insert(dsl, participants(dsl, graphName));
        insert(dsl, routineResult(dsl, graphName));
    }

    private static void insert(DSLContext dsl, Select<? extends Record10<
            String, String, String, String, String, String, String, String, String, String>> rows) {
        dsl.insertInto(GRAPHITRON_FIELD)
            .columns(GRAPHITRON_FIELD.GRAPH_NAME, GRAPHITRON_FIELD.TYPE_NAME,
                GRAPHITRON_FIELD.FIELD_NAME,
                GRAPHITRON_FIELD.FROM_SOURCE_NAME, GRAPHITRON_FIELD.FROM_SCHEMA,
                GRAPHITRON_FIELD.FROM_TABLE,
                GRAPHITRON_FIELD.TO_SOURCE_NAME, GRAPHITRON_FIELD.TO_SCHEMA,
                GRAPHITRON_FIELD.TO_TABLE, GRAPHITRON_FIELD.TARGET_BASIS)
            .select(rows)
            .execute();
    }

    /** The ordinary case: the field's navigated type binds a table and that is where its rows are. */
    private static Select<? extends Record10<
            String, String, String, String, String, String, String, String, String, String>>
            namedType(DSLContext dsl, String graphName) {
        var nv = GRAPHITRON_FIELD_NAVIGATION;
        var tgt = GRAPHITRON_TABLETYPE.as("target");
        var src = GRAPHITRON_TABLETYPE.as("source");
        return dsl.select(nv.GRAPH_NAME, nv.TYPE_NAME, nv.FIELD_NAME,
                src.TABLE_SOURCE_NAME, src.TABLE_SCHEMA, src.TABLE_NAME,
                tgt.TABLE_SOURCE_NAME, tgt.TABLE_SCHEMA, tgt.TABLE_NAME,
                val("NAMED_TYPE_TABLE"))
            .from(nv)
            .join(tgt).on(tgt.GRAPH_NAME.eq(nv.GRAPH_NAME),
                tgt.TYPE_NAME.eq(nv.NAVIGATED_TYPE_NAME))
            .leftJoin(src).on(src.GRAPH_NAME.eq(nv.GRAPH_NAME), src.TYPE_NAME.eq(nv.TYPE_NAME))
            .where(nv.GRAPH_NAME.eq(graphName));
    }

    /**
     * One target per table-bound participant, where the container binds nothing of its own.
     * Distinct on the arriving table: two participants over one table are one target, and keeping
     * them apart would mean two rows the key cannot tell apart.
     */
    private static Select<? extends Record10<
            String, String, String, String, String, String, String, String, String, String>>
            participants(DSLContext dsl, String graphName) {
        var nv = GRAPHITRON_FIELD_NAVIGATION;
        var m = GRAPHQL_POLY_MEMBER;
        var tgt = GRAPHITRON_TABLETYPE.as("target");
        var src = GRAPHITRON_TABLETYPE.as("source");
        return dsl.selectDistinct(nv.GRAPH_NAME, nv.TYPE_NAME, nv.FIELD_NAME,
                src.TABLE_SOURCE_NAME, src.TABLE_SCHEMA, src.TABLE_NAME,
                tgt.TABLE_SOURCE_NAME, tgt.TABLE_SCHEMA, tgt.TABLE_NAME,
                val("PARTICIPANT_TABLE"))
            .from(nv)
            .join(m).on(m.GRAPH_NAME.eq(nv.GRAPH_NAME),
                m.CONTAINER_NAME.eq(nv.NAVIGATED_TYPE_NAME))
            .join(tgt).on(tgt.GRAPH_NAME.eq(m.GRAPH_NAME), tgt.TYPE_NAME.eq(m.MEMBER_TYPE_NAME))
            .leftJoin(src).on(src.GRAPH_NAME.eq(nv.GRAPH_NAME), src.TYPE_NAME.eq(nv.TYPE_NAME))
            .where(nv.GRAPH_NAME.eq(graphName))
            .and(containerBindsNothing(nv.GRAPH_NAME, nv.NAVIGATED_TYPE_NAME));
    }

    /**
     * The chain ends on a routine, so the routine's result binds the return and is the target. The
     * precondition is exactly that the navigated type binds nothing: where a {@code @reference} hop
     * follows the routine the return must be {@code @table} bound, and the first rule answers there,
     * so the written order needs no comparison here.
     *
     * <p>The routine's name is resolved where it was written rather than through a relation of
     * resolved spellings, which is the same join the table binding makes: the graph's catalog
     * sources, the case folds capture stored beside the spelling, and exactly one candidate. A name
     * two schemas both declare resolves to two functions and to no row.
     */
    private static Select<? extends Record10<
            String, String, String, String, String, String, String, String, String, String>>
            routineResult(DSLContext dsl, String graphName) {
        var r = GRAPHITRON_ROUTINE;
        var later = GRAPHITRON_ROUTINE.as("later");
        var gs = STORE_GRAPH_SOURCE;
        var st = SQL_TABLE;
        var nv = GRAPHITRON_FIELD_NAVIGATION;
        var src = GRAPHITRON_TABLETYPE.as("source");

        var resolved = dsl.select(r.GRAPH_NAME, r.TYPE_NAME, r.FIELD_NAME,
                st.SOURCE_NAME, st.TABLE_SCHEMA, st.TABLE_NAME,
                count().over(partitionBy(r.GRAPH_NAME, r.TYPE_NAME, r.FIELD_NAME))
                    .as("candidates"))
            .from(r)
            .join(gs).on(gs.GRAPH_NAME.eq(r.GRAPH_NAME))
            .join(st).on(st.SOURCE_NAME.eq(gs.SOURCE_NAME),
                st.TABLE_TYPE.eq("FUNCTION"),
                st.TABLE_NAME_UPPER.eq(r.ROUTINE_REF_NAME_PART_UPPER))
            .and(r.ROUTINE_REF_NAMESPACE_PART_UPPER.isNull()
                .or(st.TABLE_SCHEMA_UPPER.eq(r.ROUTINE_REF_NAMESPACE_PART_UPPER)))
            .where(r.GRAPH_NAME.eq(graphName))
            // The chain carries one routine node; where a schema spells several the last one is
            // the node the chain start already reads, so this reads the same application.
            .and(r.ORDINAL.eq(select(max(later.ORDINAL)).from(later)
                .where(later.GRAPH_NAME.eq(r.GRAPH_NAME), later.TYPE_NAME.eq(r.TYPE_NAME),
                    later.FIELD_NAME.eq(r.FIELD_NAME))))
            .asTable("resolved");

        return dsl.select(
                resolved.field(r.GRAPH_NAME), resolved.field(r.TYPE_NAME),
                resolved.field(r.FIELD_NAME),
                src.TABLE_SOURCE_NAME, src.TABLE_SCHEMA, src.TABLE_NAME,
                resolved.field(st.SOURCE_NAME), resolved.field(st.TABLE_SCHEMA),
                resolved.field(st.TABLE_NAME),
                val("ROUTINE_RESULT"))
            .from(resolved)
            .join(nv).on(nv.GRAPH_NAME.eq(resolved.field(r.GRAPH_NAME)),
                nv.TYPE_NAME.eq(resolved.field(r.TYPE_NAME)),
                nv.FIELD_NAME.eq(resolved.field(r.FIELD_NAME)))
            .leftJoin(src).on(src.GRAPH_NAME.eq(nv.GRAPH_NAME), src.TYPE_NAME.eq(nv.TYPE_NAME))
            .where(field(name("resolved", "candidates"), Integer.class).eq(1))
            .and(containerBindsNothing(nv.GRAPH_NAME, nv.NAVIGATED_TYPE_NAME))
            .andNotExists(selectOne().from(GRAPHQL_POLY_MEMBER)
                .where(GRAPHQL_POLY_MEMBER.GRAPH_NAME.eq(nv.GRAPH_NAME),
                    GRAPHQL_POLY_MEMBER.CONTAINER_NAME.eq(nv.NAVIGATED_TYPE_NAME)));
    }

    /**
     * The precondition both lower rules share, stated once: the navigated type binds no table of
     * its own, so the first rule found no target and this one is not contending with it.
     */
    private static Condition containerBindsNothing(Field<String> graph,
                                                   Field<String> typeName) {
        var bound = GRAPHITRON_TABLETYPE.as("bound");
        return notExists(selectOne().from(bound)
            .where(bound.GRAPH_NAME.eq(graph), bound.TYPE_NAME.eq(typeName)));
    }
}
