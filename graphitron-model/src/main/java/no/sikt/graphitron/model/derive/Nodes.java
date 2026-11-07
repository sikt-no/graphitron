package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;
import org.jooq.Table;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_NODE_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_DIRECTIVE_APPLICATION;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_NODE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_TABLETYPE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_POLY_MEMBER;
import static no.sikt.graphitron.model.Tables.SQL_NODE_METADATA;
import static org.jooq.impl.DSL.denseRank;
import static org.jooq.impl.DSL.exists;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.not;
import static org.jooq.impl.DSL.partitionBy;
import static org.jooq.impl.DSL.selectOne;
import static org.jooq.impl.DSL.val;

/**
 * The capture-cadence writer of {@code graphitron_node}: which of a graph's types are nodes,
 * from every source that can make one, and the wire type id each answers to.
 *
 * <p>Two sources and one precondition. A type is a node because it carries {@code @node}, or
 * because it implements the Relay {@code Node} interface over a table whose generated class
 * publishes node metadata the catalog could use. Either way it must be table bound
 * first: {@code @node} only takes effect on a type that also carries {@code @table}, so both arms
 * read {@code graphitron_tabletype} and a {@code @node} on an unbound type stays in
 * {@code graphql_directive_application} and does not reach here. Which types those are is the anti-join between
 * the two, and that is where an author is told.
 *
 * <p>The type id is resolved rather than carried as written, in three tiers tried in order: what the
 * author declared, what the backing class publishes, and the type's own name. The last always
 * answers, so the column is never null and no reader needs a fallback. Which tier answered rides
 * along, because a diagnostic about colliding ids has to say where each came from.
 *
 * <p>A {@code @node(resolvable: false)} stub is a node like any other here, its id resolved on the
 * same tiers, and carries {@code resolvable} false so the readers that fetch by id pass it by.
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
        var a = GRAPHQL_DIRECTIVE_APPLICATION;
        var n = GRAPHITRON_AST_NODE_ENTRY;
        var m = SQL_NODE_METADATA;
        var i = GRAPHQL_POLY_MEMBER;

        // A node is a table-bound type that either says so or is published as one.
        var declared = dsl.select(tt.GRAPH_NAME, tt.TYPE_NAME)
            .from(tt)
            .join(a).on(a.GRAPH_NAME.eq(tt.GRAPH_NAME), a.COORDINATE.eq(tt.TYPE_NAME),
                a.DIRECTIVE_NAME.eq("node"), a.ORDINAL.eq(0))
            .where(tt.GRAPH_NAME.eq(graphName));
        var published = dsl.select(tt.GRAPH_NAME, tt.TYPE_NAME)
            .from(tt)
            .join(m).on(m.SOURCE_NAME.eq(tt.TABLE_SOURCE_NAME),
                m.TABLE_SCHEMA.eq(tt.TABLE_SCHEMA), m.TABLE_NAME.eq(tt.TABLE_NAME))
            .where(tt.GRAPH_NAME.eq(graphName))
            .and(exists(selectOne().from(i)
                .where(i.GRAPH_NAME.eq(tt.GRAPH_NAME), i.MEMBER_TYPE_NAME.eq(tt.TYPE_NAME),
                    i.CONTAINER_NAME.eq("Node"), i.CONTAINER_KIND.eq("INTERFACE"))));
        Table<?> nodes = declared.union(published).asTable("nodes");
        var g = nodes.field(tt.GRAPH_NAME);
        var t = nodes.field(tt.TYPE_NAME);

        // The three tiers, lowest precedence number winning; the last always answers. The declared
        // one is the type's first @node application, which is the one a type answers to.
        var arms = dsl.select(g, t, n.TYPE_ID, val("SDL_DECLARED").as("origin"),
                val(0).as("precedence"))
            .from(nodes)
            .join(a).on(a.GRAPH_NAME.eq(g), a.COORDINATE.eq(t), a.DIRECTIVE_NAME.eq("node"),
                a.ORDINAL.eq(0))
            .join(n).on(n.GRAPH_NAME.eq(a.GRAPH_NAME), n.SOURCE_NAME.eq(a.SOURCE_NAME),
                n.SOURCE_LINE.eq(a.SOURCE_LINE), n.SOURCE_COLUMN.eq(a.SOURCE_COLUMN))
            .where(n.TYPE_ID.isNotNull())
            .unionAll(dsl.select(g, t, m.TYPE_ID, val("JOOQ_METADATA"), val(1))
                .from(nodes)
                .join(tt).on(tt.GRAPH_NAME.eq(g), tt.TYPE_NAME.eq(t))
                .join(m).on(m.SOURCE_NAME.eq(tt.TABLE_SOURCE_NAME),
                    m.TABLE_SCHEMA.eq(tt.TABLE_SCHEMA), m.TABLE_NAME.eq(tt.TABLE_NAME))
                .where(m.TYPE_ID.isNotNull()))
            .unionAll(dsl.select(g, t, t, val("TYPE_NAME"), val(2)).from(nodes))
            .asTable("arms");

        var ranked = dsl.select(
                arms.field(g), arms.field(t), arms.field(n.TYPE_ID),
                arms.field("origin", String.class),
                denseRank().over(partitionBy(arms.field(g), arms.field(t))
                    .orderBy(arms.field("precedence", Integer.class))).as("tier"))
            .from(arms)
            .asTable("ranked");

        // A node is resolvable unless its first @node says otherwise, the published arm included.
        var stub = exists(selectOne().from(a)
            .join(n).on(n.GRAPH_NAME.eq(a.GRAPH_NAME), n.SOURCE_NAME.eq(a.SOURCE_NAME),
                n.SOURCE_LINE.eq(a.SOURCE_LINE), n.SOURCE_COLUMN.eq(a.SOURCE_COLUMN))
            .where(a.GRAPH_NAME.eq(ranked.field(g)), a.COORDINATE.eq(ranked.field(t)),
                a.DIRECTIVE_NAME.eq("node"), a.ORDINAL.eq(0), n.RESOLVABLE.isFalse()));

        dsl.insertInto(GRAPHITRON_NODE)
            .columns(GRAPHITRON_NODE.GRAPH_NAME, GRAPHITRON_NODE.TYPE_NAME,
                GRAPHITRON_NODE.TYPE_ID, GRAPHITRON_NODE.TYPE_ID_ORIGIN, GRAPHITRON_NODE.RESOLVABLE)
            .select(dsl
                .select(ranked.field(g), ranked.field(t), ranked.field(n.TYPE_ID),
                    ranked.field("origin", String.class), field(not(stub)))
                .from(ranked)
                .where(field(name("ranked", "tier"), Integer.class).eq(1)))
            .execute();
    }
}
