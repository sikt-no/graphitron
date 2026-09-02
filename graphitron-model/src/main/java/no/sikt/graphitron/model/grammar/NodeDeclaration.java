package no.sikt.graphitron.model.grammar;

import graphql.language.ObjectTypeDefinition;
import graphql.language.StringValue;
import graphql.schema.GraphQLNamedType;
import graphql.schema.GraphQLObjectType;
import no.sikt.graphitron.model.jooq.JooqCatalog;

import java.util.Locale;

import static no.sikt.graphitron.model.grammar.DirectiveArgs.argString;

/**
 * The one place that answers "is this object type a node?". An object type is a node when it
 * declares {@code @node}, or when it publishes the Relay {@code Node} contract over a
 * {@code @table} whose backing jOOQ class carries {@code __NODE_TYPE_ID} /
 * {@code __NODE_KEY_COLUMNS} (see {@code docs/manual/reference/directives/node.adoc}).
 *
 * <h3>Why this is a named predicate rather than a directive read</h3>
 * Before inference, {@code @node} presence and
 * {@code GraphitronType.NodeType} membership were the same set, so
 * several consumers read the directive straight off SDL and stayed consistent with the classifier
 * by coincidence. Inference splits the two, and each of those consumers is a place an inferred node
 * and an explicit one would otherwise behave differently: reachability seeding
 * ({@code SchemaReachability}), arrival folding ({@code ArrivalIndex}), federation entity membership
 * ({@link no.sikt.graphitron.model.schema.federation.KeyNodeSynthesiser}), and the LSP node view
 * ({@code CatalogBuilder}). They all call this instead.
 *
 * <p>The predicate deliberately sits <em>above</em> classification rather than reading the
 * classified registry: two of its consumers run before any type is classified. Reachability
 * computes the seed set that decides <em>which</em> types get classified, and the federation
 * synthesiser runs on the raw {@link graphql.schema.idl.TypeDefinitionRegistry} while assembling
 * the attributed registry. Both facts it needs, the SDL declarations and the jOOQ catalog, are
 * available there, so it takes only a {@link JooqCatalog}.
 *
 * <p>{@code TypeBuilder}'s own promotion gate does not call {@link #isNodeType(GraphQLObjectType)}:
 * it needs the metadata <em>values</em> to build the type, so it re-derives the same conjunction
 * from {@link #implementsNode(GraphQLObjectType)}, {@link #boundTableName(GraphQLObjectType)} and
 * its own probe. Sharing those helpers is what keeps the two from drifting on the inference path,
 * where they agree by construction.
 *
 * <p>They deliberately part on one shape. {@code @node} without {@code implements Node} reads as a
 * node here, because this is a declaration-level question, while the classifier rejects the type:
 * the Relay interface requirement is the classifier's to enforce. The consequence is contained,
 * since such a type fails the build on that rejection whether or not it was seeded here or given a
 * synthesised federation key. But widening this predicate does not on its own widen what classifies
 * as a node, nor the reverse; a change to the rule belongs in both places.
 *
 * <h3>Malformed metadata</h3>
 * A table whose {@code __NODE_*} constants are present but fail validation reads as
 * <em>not</em> carrying metadata here, because {@link JooqCatalog#nodeIdMetadata(String)} is empty
 * for that state. {@code TypeBuilder} rejects such a type outright ahead of the gate, so nothing is
 * gained by seeding it; the effect is that a malformed-metadata type reachable through no field is
 * pruned rather than classified, exactly as it was before inference existed.
 */
public final class NodeDeclaration {

    /** The {@code @table} directive. Named here because this is the one place that reads it. */
    public static final String DIR_TABLE = "table";

    /** The {@code @node} directive. Named here because this is the one place that reads it. */
    public static final String DIR_NODE = "node";

    /** The {@code name} argument, as {@code @table(name:)} spells it. */
    public static final String ARG_NAME = "name";


    private final JooqCatalog catalog;

    /**
     * @param catalog the jOOQ catalog to probe for {@code __NODE_*} metadata, or {@code null} for a
     *                context that has none (the same nullable-catalog {@code BuildContext} accepts
     *                from tests that focus on the other half of the plumbing). A null catalog reads
     *                as "no table publishes node metadata", so nodehood reduces to {@code @node}
     *                presence, which is what the predicate answered before inference existed.
     */
    public NodeDeclaration(JooqCatalog catalog) {
        this.catalog = catalog;
    }

    /**
     * Whether {@code obj} is a node type: {@code @node} is declared, or nodehood is inferred from
     * {@code implements Node} plus a {@code @table} whose backing class publishes node metadata.
     */
    public boolean isNodeType(GraphQLObjectType obj) {
        if (obj.hasAppliedDirective(DIR_NODE)) {
            return true;
        }
        return obj.hasAppliedDirective(DIR_TABLE)
            && implementsNode(obj)
            && hasNodeIdMetadata(boundTableName(obj));
    }

    /**
     * Raw-registry sibling of {@link #isNodeType(GraphQLObjectType)}, for the pre-classification
     * stages that hold {@link ObjectTypeDefinition}s rather than an assembled schema. Same rule,
     * read off the AST.
     */
    public boolean isNodeType(ObjectTypeDefinition obj) {
        if (hasDirective(obj, DIR_NODE)) {
            return true;
        }
        return hasDirective(obj, DIR_TABLE)
            && implementsNode(obj)
            && hasNodeIdMetadata(boundTableName(obj));
    }

    /**
     * Whether the backing jOOQ class of {@code tableSqlName} publishes well-formed node-id
     * metadata. The name is the value {@code @table(name:)} carries, which
     * {@link JooqCatalog#nodeIdMetadata(String)} resolves qualified or unqualified.
     */
    public boolean hasNodeIdMetadata(String tableSqlName) {
        return catalog != null && catalog.nodeIdMetadata(tableSqlName).isPresent();
    }

    /**
     * The SQL table name a {@code @table} directive binds: the {@code name:} argument when present,
     * otherwise the lowercased GraphQL type name. Shared with {@code TypeBuilder} so the gate and
     * the predicate resolve the same table for the same type.
     */
    public static String boundTableName(GraphQLObjectType obj) {
        return argString(obj, DIR_TABLE, ARG_NAME).orElseGet(() -> defaultTableName(obj.getName()));
    }

    /** Raw-registry sibling of {@link #boundTableName(GraphQLObjectType)}. */
    public static String boundTableName(ObjectTypeDefinition obj) {
        for (var d : obj.getDirectives()) {
            if (!DIR_TABLE.equals(d.getName())) continue;
            var arg = d.getArgument(ARG_NAME);
            if (arg != null && arg.getValue() instanceof StringValue sv) {
                return sv.getValue();
            }
        }
        return defaultTableName(obj.getName());
    }

    /** The table name a {@code @table} with no {@code name:} argument binds. */
    public static String defaultTableName(String graphQlTypeName) {
        return graphQlTypeName.toLowerCase(Locale.ROOT);
    }

    /** Whether {@code obj} publishes the Relay {@code Node} contract in its {@code implements} clause. */
    public static boolean implementsNode(GraphQLObjectType obj) {
        return obj.getInterfaces().stream()
            .anyMatch(i -> "Node".equals(((GraphQLNamedType) i).getName()));
    }

    /** Raw-registry sibling of {@link #implementsNode(GraphQLObjectType)}. */
    public static boolean implementsNode(ObjectTypeDefinition obj) {
        return obj.getImplements().stream()
            .anyMatch(t -> t instanceof graphql.language.TypeName tn && "Node".equals(tn.getName()));
    }

    private static boolean hasDirective(ObjectTypeDefinition obj, String name) {
        return obj.getDirectives().stream().anyMatch(d -> name.equals(d.getName()));
    }
}
