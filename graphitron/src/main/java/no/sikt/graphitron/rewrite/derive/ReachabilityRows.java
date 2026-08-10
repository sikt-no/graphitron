package no.sikt.graphitron.rewrite.derive;

import no.sikt.graphitron.rewrite.schema.DeclaredDirectives;
import org.jooq.DSLContext;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_FEDERATION_KEY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_NODE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_TABLE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_ARGUMENT;
import static no.sikt.graphitron.model.Tables.GRAPHQL_DIRECTIVE_ARGUMENT;
import static no.sikt.graphitron.model.Tables.GRAPHQL_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHQL_IMPLEMENTS;
import static no.sikt.graphitron.model.Tables.GRAPHQL_ROOT_OPERATION;
import static no.sikt.graphitron.model.Tables.GRAPHQL_TYPE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_UNION_MEMBER;
import static no.sikt.graphitron.model.Tables.INTENT_TYPE_DOMAIN;
import static org.jooq.impl.DSL.exists;
import static org.jooq.impl.DSL.notExists;
import static org.jooq.impl.DSL.select;
import static org.jooq.impl.DSL.selectOne;
import static org.jooq.impl.DSL.val;

/**
 * The capture-cadence writer of {@code intent_type_domain}: the classification domain's type
 * members, derived as a transitive closure over the captured SDL edges. Runs inside capture's
 * own transaction after the flush, clears the run's graph partition first (the cadence
 * doctrine's clearing rule), and re-derives, so on any settled store the relation is current
 * for every captured graph. The relation is a table rather than a view because H2 has no safe
 * recursive view form for a cyclic type graph; the loop here is the semi-naive
 * {@code INSERT..SELECT} stratum, monotone and bounded by the graph's type count, with the
 * bound enforced so a non-monotone edit fails loudly instead of hanging a build.
 *
 * <p>The seeds transcribe {@link no.sikt.graphitron.rewrite.SchemaReachability}'s seed scan:
 * root operation bindings, {@code @node} types, {@code @table} types implementing {@code Node}
 * (over-approximating node inference until the jOOQ node-metadata constants are captured),
 * {@code @key} carriers, and the argument types of directive definitions that survive into the
 * emitted schema, where "survives" is everything outside {@link DeclaredDirectives#names()},
 * bound as a query parameter so the vocabulary lives in one place. The descent edges likewise
 * transcribe its child function: field targets and argument types of every reached
 * field-bearing type, union members, {@code implements} in the declaration direction from both
 * kinds, and the reverse interface-to-implementor edge narrowed to object implementors, each
 * landing only on captured type names.
 */
public final class ReachabilityRows {

    private ReachabilityRows() {}

    /** Clears and re-derives the graph's domain partition; see the class javadoc. */
    public static void derive(DSLContext dsl, String graphName) {
        dsl.deleteFrom(INTENT_TYPE_DOMAIN)
            .where(INTENT_TYPE_DOMAIN.GRAPH_NAME.eq(graphName))
            .execute();
        seed(dsl, graphName);
        int bound = dsl.fetchCount(GRAPHQL_TYPE, GRAPHQL_TYPE.GRAPH_NAME.eq(graphName));
        for (int pass = 0; expand(dsl, graphName) > 0; pass++) {
            if (pass > bound) {
                throw new IllegalStateException(
                    "intent_type_domain closure for graph '" + graphName + "' did not converge in "
                        + bound + " passes; the frontier statement has stopped being monotone");
            }
        }
    }

    private static void seed(DSLContext dsl, String graphName) {
        var d = INTENT_TYPE_DOMAIN;
        var seeds =
            select(GRAPHQL_ROOT_OPERATION.GRAPH_NAME, GRAPHQL_ROOT_OPERATION.TYPE_NAME)
                .from(GRAPHQL_ROOT_OPERATION)
                .where(GRAPHQL_ROOT_OPERATION.GRAPH_NAME.eq(graphName))
            .union(select(GRAPHITRON_NODE.GRAPH_NAME, GRAPHITRON_NODE.TYPE_NAME)
                .from(GRAPHITRON_NODE)
                .where(GRAPHITRON_NODE.GRAPH_NAME.eq(graphName)))
            .union(select(GRAPHITRON_TABLE.GRAPH_NAME, GRAPHITRON_TABLE.TYPE_NAME)
                .from(GRAPHITRON_TABLE)
                .where(GRAPHITRON_TABLE.GRAPH_NAME.eq(graphName))
                .and(exists(selectOne().from(GRAPHQL_IMPLEMENTS)
                    .where(GRAPHQL_IMPLEMENTS.GRAPH_NAME.eq(GRAPHITRON_TABLE.GRAPH_NAME))
                    .and(GRAPHQL_IMPLEMENTS.TYPE_NAME.eq(GRAPHITRON_TABLE.TYPE_NAME))
                    .and(GRAPHQL_IMPLEMENTS.INTERFACE_NAME.eq("Node")))))
            .union(select(GRAPHITRON_FEDERATION_KEY.GRAPH_NAME, GRAPHITRON_FEDERATION_KEY.TYPE_NAME)
                .from(GRAPHITRON_FEDERATION_KEY)
                .where(GRAPHITRON_FEDERATION_KEY.GRAPH_NAME.eq(graphName)))
            .union(select(GRAPHQL_DIRECTIVE_ARGUMENT.GRAPH_NAME, GRAPHQL_DIRECTIVE_ARGUMENT.NAMED_TYPE)
                .from(GRAPHQL_DIRECTIVE_ARGUMENT)
                .where(GRAPHQL_DIRECTIVE_ARGUMENT.GRAPH_NAME.eq(graphName))
                .and(GRAPHQL_DIRECTIVE_ARGUMENT.DIRECTIVE_NAME.notIn(DeclaredDirectives.names())))
            .asTable("seeds", "graph_name", "type_name");
        dsl.insertInto(d, d.GRAPH_NAME, d.TYPE_NAME)
            .select(select(seeds.field("graph_name", String.class), seeds.field("type_name", String.class))
                .from(seeds)
                .where(exists(selectOne().from(GRAPHQL_TYPE)
                    .where(GRAPHQL_TYPE.GRAPH_NAME.eq(seeds.field("graph_name", String.class)))
                    .and(GRAPHQL_TYPE.TYPE_NAME.eq(seeds.field("type_name", String.class))))))
            .execute();
    }

    /** One frontier pass: inserts every captured type one edge beyond the current members. */
    private static int expand(DSLContext dsl, String graphName) {
        var d = INTENT_TYPE_DOMAIN;
        var member = INTENT_TYPE_DOMAIN.as("member");
        var implementor = GRAPHQL_TYPE.as("implementor");
        var edges =
            select(GRAPHQL_FIELD.GRAPH_NAME, GRAPHQL_FIELD.NAMED_TYPE)
                .from(GRAPHQL_FIELD)
                .join(member).on(member.GRAPH_NAME.eq(GRAPHQL_FIELD.GRAPH_NAME)
                    .and(member.TYPE_NAME.eq(GRAPHQL_FIELD.TYPE_NAME)))
                .where(GRAPHQL_FIELD.GRAPH_NAME.eq(graphName))
            .union(select(GRAPHQL_ARGUMENT.GRAPH_NAME, GRAPHQL_ARGUMENT.NAMED_TYPE)
                .from(GRAPHQL_ARGUMENT)
                .join(member).on(member.GRAPH_NAME.eq(GRAPHQL_ARGUMENT.GRAPH_NAME)
                    .and(member.TYPE_NAME.eq(GRAPHQL_ARGUMENT.TYPE_NAME)))
                .where(GRAPHQL_ARGUMENT.GRAPH_NAME.eq(graphName)))
            .union(select(GRAPHQL_UNION_MEMBER.GRAPH_NAME, GRAPHQL_UNION_MEMBER.MEMBER_TYPE_NAME)
                .from(GRAPHQL_UNION_MEMBER)
                .join(member).on(member.GRAPH_NAME.eq(GRAPHQL_UNION_MEMBER.GRAPH_NAME)
                    .and(member.TYPE_NAME.eq(GRAPHQL_UNION_MEMBER.UNION_NAME)))
                .where(GRAPHQL_UNION_MEMBER.GRAPH_NAME.eq(graphName)))
            .union(select(GRAPHQL_IMPLEMENTS.GRAPH_NAME, GRAPHQL_IMPLEMENTS.INTERFACE_NAME)
                .from(GRAPHQL_IMPLEMENTS)
                .join(member).on(member.GRAPH_NAME.eq(GRAPHQL_IMPLEMENTS.GRAPH_NAME)
                    .and(member.TYPE_NAME.eq(GRAPHQL_IMPLEMENTS.TYPE_NAME)))
                .where(GRAPHQL_IMPLEMENTS.GRAPH_NAME.eq(graphName)))
            .union(select(GRAPHQL_IMPLEMENTS.GRAPH_NAME, GRAPHQL_IMPLEMENTS.TYPE_NAME)
                .from(GRAPHQL_IMPLEMENTS)
                .join(member).on(member.GRAPH_NAME.eq(GRAPHQL_IMPLEMENTS.GRAPH_NAME)
                    .and(member.TYPE_NAME.eq(GRAPHQL_IMPLEMENTS.INTERFACE_NAME)))
                .join(implementor).on(implementor.GRAPH_NAME.eq(GRAPHQL_IMPLEMENTS.GRAPH_NAME)
                    .and(implementor.TYPE_NAME.eq(GRAPHQL_IMPLEMENTS.TYPE_NAME))
                    .and(implementor.KIND.eq("OBJECT")))
                .where(GRAPHQL_IMPLEMENTS.GRAPH_NAME.eq(graphName)))
            .asTable("edges", "graph_name", "target");
        var target = edges.field("target", String.class);
        return dsl.insertInto(d, d.GRAPH_NAME, d.TYPE_NAME)
            .select(select(val(graphName), target).from(edges)
                .where(exists(selectOne().from(GRAPHQL_TYPE)
                    .where(GRAPHQL_TYPE.GRAPH_NAME.eq(graphName))
                    .and(GRAPHQL_TYPE.TYPE_NAME.eq(target))))
                .and(notExists(selectOne().from(d)
                    .where(d.GRAPH_NAME.eq(graphName))
                    .and(d.TYPE_NAME.eq(target)))))
            .execute();
    }
}
