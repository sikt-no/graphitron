package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Table;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_FIELD_NODE_ID_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_INPUT_VALUE_NODE_ID_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_NODE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_NODE_ID_CANDIDATE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_ARGUMENT_ELEMENT;
import static no.sikt.graphitron.model.Tables.GRAPHQL_DIRECTIVE_APPLICATION;
import static no.sikt.graphitron.model.Tables.GRAPHQL_ELEMENT;
import static no.sikt.graphitron.model.Tables.GRAPHQL_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHQL_FIELD_ELEMENT;
import static no.sikt.graphitron.model.Tables.GRAPHQL_POLY_MEMBER;

/**
 * The capture-cadence writer of {@code graphitron_node_id_candidate}: the node types a bare
 * {@code @nodeId} at a coordinate may be the ID of.
 *
 * <p>A bare application is an entry row naming no type, at either site, and its coordinate is the
 * one {@code graphql_directive_application} records at the same position. Its candidates are read
 * by type: on the {@code id} field a type declares for {@code Node}, that type; on an argument the
 * field's return type, or each member of an interface or union return. Only {@code Node.id} binds
 * to the type it is declared on, so any other output field draws nothing here. Every arm joins
 * {@code graphitron_node}, so a type that is not a node is no candidate. A table-bound type is
 * authoritative where one answers, so an argument takes its field's return type whether or not it
 * also carries {@code @reference}.
 *
 * <p>Runs in the graphitron anchor, after {@link Nodes}, which it keys into. The entries are the
 * same gatherer's capture and the elements the SDL's, both written before it.
 */
public final class NodeIdCandidates {

    private NodeIdCandidates() {}

    /** Clears and re-derives the graph's candidates; see the class javadoc. */
    public static void derive(DSLContext dsl, String graphName) {
        var c = GRAPHITRON_NODE_ID_CANDIDATE;
        dsl.deleteFrom(c).where(c.GRAPH_NAME.eq(graphName)).execute();

        var a = GRAPHQL_DIRECTIVE_APPLICATION;
        var fe = GRAPHITRON_AST_FIELD_NODE_ID_ENTRY;
        var ie = GRAPHITRON_AST_INPUT_VALUE_NODE_ID_ENTRY;

        // The bare applications, at whichever site, by the coordinate they were written on.
        Table<?> bare = dsl.select(a.GRAPH_NAME, a.COORDINATE)
            .from(a)
            .join(fe).on(fe.GRAPH_NAME.eq(a.GRAPH_NAME), fe.SOURCE_NAME.eq(a.SOURCE_NAME),
                fe.SOURCE_LINE.eq(a.SOURCE_LINE), fe.SOURCE_COLUMN.eq(a.SOURCE_COLUMN))
            .where(a.GRAPH_NAME.eq(graphName), a.DIRECTIVE_NAME.eq("nodeId"),
                fe.NODE_TYPE_REF.isNull())
            .union(dsl.select(a.GRAPH_NAME, a.COORDINATE)
                .from(a)
                .join(ie).on(ie.GRAPH_NAME.eq(a.GRAPH_NAME), ie.SOURCE_NAME.eq(a.SOURCE_NAME),
                    ie.SOURCE_LINE.eq(a.SOURCE_LINE), ie.SOURCE_COLUMN.eq(a.SOURCE_COLUMN))
                .where(a.GRAPH_NAME.eq(graphName), a.DIRECTIVE_NAME.eq("nodeId"),
                    ie.NODE_TYPE_REF.isNull()))
            .asTable("bare");
        Field<String> g = bare.field(a.GRAPH_NAME);
        Field<String> coordinate = bare.field(a.COORDINATE);

        var e = GRAPHQL_ELEMENT;
        var fld = GRAPHQL_FIELD_ELEMENT;
        var arg = GRAPHQL_ARGUMENT_ELEMENT;
        var f = GRAPHQL_FIELD;
        var m = GRAPHQL_POLY_MEMBER;
        var n = GRAPHITRON_NODE;

        // The id a type declares for Node: the type it is declared on.
        var node = GRAPHQL_POLY_MEMBER.as("node");
        var containing = dsl.select(g, coordinate, n.TYPE_NAME)
            .from(bare)
            .join(e).on(e.GRAPH_NAME.eq(g), e.COORDINATE.eq(coordinate), e.ELEMENT_KIND.eq("FIELD"))
            .join(fld).on(fld.GRAPH_NAME.eq(g), fld.COORDINATE.eq(coordinate),
                fld.FIELD_NAME.eq("id"))
            .join(node).on(node.GRAPH_NAME.eq(g), node.MEMBER_TYPE_NAME.eq(fld.TYPE_NAME),
                node.CONTAINER_NAME.eq("Node"), node.CONTAINER_KIND.eq("INTERFACE"))
            .join(n).on(n.GRAPH_NAME.eq(g), n.TYPE_NAME.eq(fld.TYPE_NAME));
        // An argument: the type the field returns, which answers before any path does.
        var returned = dsl.select(g, coordinate, n.TYPE_NAME)
            .from(bare)
            .join(arg).on(arg.GRAPH_NAME.eq(g), arg.COORDINATE.eq(coordinate))
            .join(f).on(f.GRAPH_NAME.eq(g), f.TYPE_NAME.eq(arg.TYPE_NAME),
                f.FIELD_NAME.eq(arg.FIELD_NAME))
            .join(n).on(n.GRAPH_NAME.eq(g), n.TYPE_NAME.eq(f.NAMED_TYPE));
        // An argument on a field returning an interface or a union: each member.
        var member = dsl.select(g, coordinate, n.TYPE_NAME)
            .from(bare)
            .join(arg).on(arg.GRAPH_NAME.eq(g), arg.COORDINATE.eq(coordinate))
            .join(f).on(f.GRAPH_NAME.eq(g), f.TYPE_NAME.eq(arg.TYPE_NAME),
                f.FIELD_NAME.eq(arg.FIELD_NAME))
            .join(m).on(m.GRAPH_NAME.eq(g), m.CONTAINER_NAME.eq(f.NAMED_TYPE))
            .join(n).on(n.GRAPH_NAME.eq(g), n.TYPE_NAME.eq(m.MEMBER_TYPE_NAME));

        dsl.insertInto(c)
            .columns(c.GRAPH_NAME, c.COORDINATE, c.NODE_TYPE_NAME)
            .select(containing.union(returned).union(member))
            .execute();
    }
}
