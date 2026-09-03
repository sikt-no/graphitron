package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_TABLE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_TABLETYPE;
import static no.sikt.graphitron.model.Tables.SQL_TABLE;
import static no.sikt.graphitron.model.Tables.STORE_GRAPH_SOURCE;
import static org.jooq.impl.DSL.coalesce;
import static org.jooq.impl.DSL.count;
import static org.jooq.impl.DSL.partitionBy;

/**
 * The capture-cadence writer of {@code graphitron_tabletype}: which catalog table each graph type
 * resolves to, for the types where the catalog answered with exactly one.
 *
 * <p>Called as a stage of the graphitron gatherer, because everything it reads has already been
 * written there: the {@code @table} decode is that gatherer's own and has just flushed, and the
 * catalog crawler runs before it by declared dependency, so the resolution has both sides in hand
 * at the earliest point either exists. Stated here rather than inside the gatherer so the seeding
 * harness can make the same call, a case that seeds a {@code @table} and a table expecting the
 * binding to follow being the same rule and not a second one.
 *
 * <p>Only the settled bindings are rows, and that is the relation's whole claim rather than a
 * filter its readers apply. A spelling two schemas both declare resolves to two tables and to no row
 * here; which types those are is the anti-join against the {@code @table} decode, so ambiguity is
 * still answerable and is answered where the author's writing lives.
 *
 * <p>The key is the graph and the type, which is the settledness itself. A relation admitting an
 * ambiguous binding could carry neither that key nor the cascading foreign key into
 * {@code sql_table}, an unresolved row naming no table to point at.
 *
 * <p>A type with no {@code name:} binds by its own name, and the three root operation types are
 * excluded because a table named after one of them would otherwise bind it. Both are resolution
 * rules and live here; the decode stays what the author wrote.
 */
public final class TableTypes {

    private TableTypes() {}

    /** Clears and re-derives the graph's settled bindings; see the class javadoc. */
    public static void derive(DSLContext dsl, String graphName) {
        var t = GRAPHITRON_TABLE;
        var m = STORE_GRAPH_SOURCE;
        var st = SQL_TABLE;
        // Cleared first so the call is idempotent: capture makes it once per graph, and a caller
        // re-deriving in order to read the result makes it as often as it likes.
        dsl.deleteFrom(GRAPHITRON_TABLETYPE)
            .where(GRAPHITRON_TABLETYPE.GRAPH_NAME.eq(graphName)).execute();
        var resolved = dsl
            .select(t.GRAPH_NAME, t.TYPE_NAME, st.SOURCE_NAME, st.TABLE_SCHEMA, st.TABLE_NAME,
                count().over(partitionBy(t.GRAPH_NAME, t.TYPE_NAME)).as("candidates"))
            .from(t)
            .join(m).on(m.GRAPH_NAME.eq(t.GRAPH_NAME))
            .join(st).on(st.SOURCE_NAME.eq(m.SOURCE_NAME))
            .and(st.TABLE_NAME_UPPER.eq(coalesce(t.TABLE_REF_NAME_PART_UPPER, t.TYPE_NAME_UPPER)))
            .and(t.TABLE_REF_NAMESPACE_PART_UPPER.isNull()
                .or(st.TABLE_SCHEMA_UPPER.eq(t.TABLE_REF_NAMESPACE_PART_UPPER)))
            .where(t.GRAPH_NAME.eq(graphName))
            .and(t.TYPE_NAME.notIn("Query", "Mutation", "Subscription"))
            .asTable("resolved");
        dsl.insertInto(GRAPHITRON_TABLETYPE)
            .columns(GRAPHITRON_TABLETYPE.GRAPH_NAME, GRAPHITRON_TABLETYPE.TYPE_NAME,
                GRAPHITRON_TABLETYPE.TABLE_SOURCE_NAME, GRAPHITRON_TABLETYPE.TABLE_SCHEMA,
                GRAPHITRON_TABLETYPE.TABLE_NAME)
            .select(dsl
                .select(resolved.field(t.GRAPH_NAME), resolved.field(t.TYPE_NAME),
                    resolved.field(st.SOURCE_NAME), resolved.field(st.TABLE_SCHEMA),
                    resolved.field(st.TABLE_NAME))
                .from(resolved)
                .where(resolved.field("candidates", Integer.class).eq(1)))
            .execute();
    }
}
