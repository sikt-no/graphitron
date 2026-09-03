package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;
import org.jooq.Table;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_NODE_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_NODE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_TABLETYPE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_POLY_MEMBER;
import static no.sikt.graphitron.model.Tables.INTENT_NODE_METADATA_DEFECT;
import static no.sikt.graphitron.model.Tables.SQL_NODE_METADATA;
import static org.jooq.impl.DSL.denseRank;
import static org.jooq.impl.DSL.exists;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.notExists;
import static org.jooq.impl.DSL.partitionBy;
import static org.jooq.impl.DSL.selectOne;
import static org.jooq.impl.DSL.val;

/**
 * The capture-cadence writer of {@code graphitron_node}: which of a graph's types are nodes,
 * from every source that can make one, and the wire type id each answers to.
 *
 * <p>Two sources and one precondition. A type is a node because it carries {@code @node}, or
 * because it implements the Relay {@code Node} interface over a table whose generated class
 * publishes the node metadata and carries no defect in it. Either way it must be table bound
 * first: {@code @node} only takes effect on a type that also carries {@code @table}, so both arms
 * read {@code graphitron_tabletype} and a {@code @node} on an unbound type stays in
 * {@code graphitron_node_entry} and does not reach here. Which types those are is the anti-join between
 * the two, and that is where an author is told.
 *
 * <p>The type id is resolved rather than carried as written, in three tiers tried in order: what the
 * author declared, what the backing class publishes, and the type's own name. The last always
 * answers, so the column is never null and no reader needs a fallback. Which tier answered rides
 * along, because a diagnostic about colliding ids has to say where each came from.
 *
 * <p>The id and the key columns are independent axes and are resolved separately for that reason.
 * An author may declare the id and leave the columns to the catalog, or the reverse, so a single
 * declared-versus-inferred flag over the node would be wrong and neither relation carries one. The
 * columns are {@link NodeKeyColumns}, which runs after this and keys into what it writes.
 *
 * <p>Runs as a stage of the graphitron gatherer, after the table binding it depends on: the catalog
 * crawler is a declared dependency and has flushed, the {@code @node} decode is the gatherer's own,
 * and the binding is the stage before. Stated here rather than inside the gatherer so the seeding
 * harness makes the same call.
 */
public final class Nodes {

    private Nodes() {}

    /** Clears and re-derives the graph's nodes; see the class javadoc. */
    public static void derive(DSLContext dsl, String graphName) {
        dsl.deleteFrom(GRAPHITRON_NODE)
            .where(GRAPHITRON_NODE.GRAPH_NAME.eq(graphName)).execute();

        var tt = GRAPHITRON_TABLETYPE;
        var n = GRAPHITRON_NODE_ENTRY;
        var m = SQL_NODE_METADATA;
        var d = INTENT_NODE_METADATA_DEFECT;
        var i = GRAPHQL_POLY_MEMBER;

        // A node is a table-bound type that either says so or is published as one.
        var declared = dsl.select(tt.GRAPH_NAME, tt.TYPE_NAME)
            .from(tt)
            .join(n).on(n.GRAPH_NAME.eq(tt.GRAPH_NAME), n.TYPE_NAME.eq(tt.TYPE_NAME))
            .where(tt.GRAPH_NAME.eq(graphName));
        var published = dsl.select(tt.GRAPH_NAME, tt.TYPE_NAME)
            .from(tt)
            .join(m).on(m.SOURCE_NAME.eq(tt.TABLE_SOURCE_NAME),
                m.TABLE_SCHEMA.eq(tt.TABLE_SCHEMA), m.TABLE_NAME.eq(tt.TABLE_NAME))
            .where(tt.GRAPH_NAME.eq(graphName))
            .and(exists(selectOne().from(i)
                .where(i.GRAPH_NAME.eq(tt.GRAPH_NAME), i.MEMBER_TYPE_NAME.eq(tt.TYPE_NAME),
                    i.CONTAINER_NAME.eq("Node"), i.CONTAINER_KIND.eq("INTERFACE"))))
            .and(notExists(selectOne().from(d)
                .where(d.SOURCE_NAME.eq(m.SOURCE_NAME), d.TABLE_SCHEMA.eq(m.TABLE_SCHEMA),
                    d.TABLE_NAME.eq(m.TABLE_NAME))));
        Table<?> nodes = declared.union(published).asTable("nodes");
        var g = nodes.field(tt.GRAPH_NAME);
        var t = nodes.field(tt.TYPE_NAME);

        // The three tiers, lowest precedence number winning; the last always answers.
        var arms = dsl.select(g, t, n.TYPE_ID, val("SDL_DECLARED").as("origin"),
                val(0).as("precedence"))
            .from(nodes)
            .join(n).on(n.GRAPH_NAME.eq(g), n.TYPE_NAME.eq(t))
            .where(n.TYPE_ID.isNotNull())
            .unionAll(dsl.select(g, t, m.TYPE_ID, val("JOOQ_METADATA"), val(1))
                .from(nodes)
                .join(tt).on(tt.GRAPH_NAME.eq(g), tt.TYPE_NAME.eq(t))
                .join(m).on(m.SOURCE_NAME.eq(tt.TABLE_SOURCE_NAME),
                    m.TABLE_SCHEMA.eq(tt.TABLE_SCHEMA), m.TABLE_NAME.eq(tt.TABLE_NAME))
                .where(m.TYPE_ID.isNotNull())
                .and(notExists(selectOne().from(d)
                    .where(d.SOURCE_NAME.eq(m.SOURCE_NAME), d.TABLE_SCHEMA.eq(m.TABLE_SCHEMA),
                        d.TABLE_NAME.eq(m.TABLE_NAME)))))
            .unionAll(dsl.select(g, t, t, val("TYPE_NAME"), val(2)).from(nodes))
            .asTable("arms");

        var ranked = dsl.select(
                arms.field(g), arms.field(t), arms.field(n.TYPE_ID),
                arms.field("origin", String.class),
                denseRank().over(partitionBy(arms.field(g), arms.field(t))
                    .orderBy(arms.field("precedence", Integer.class))).as("tier"))
            .from(arms)
            .asTable("ranked");

        dsl.insertInto(GRAPHITRON_NODE)
            .columns(GRAPHITRON_NODE.GRAPH_NAME, GRAPHITRON_NODE.TYPE_NAME,
                GRAPHITRON_NODE.TYPE_ID, GRAPHITRON_NODE.TYPE_ID_ORIGIN)
            .select(dsl
                .select(ranked.field(g), ranked.field(t), ranked.field(n.TYPE_ID),
                    ranked.field("origin", String.class))
                .from(ranked)
                .where(field(name("ranked", "tier"), Integer.class).eq(1)))
            .execute();
    }
}
