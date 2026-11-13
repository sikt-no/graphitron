package no.sikt.graphitron.model.grammar;

import graphql.language.BooleanValue;
import graphql.language.ObjectTypeDefinition;
import graphql.language.StringValue;
import graphql.schema.GraphQLNamedType;
import graphql.schema.GraphQLObjectType;
import no.sikt.graphitron.model.jooq.JooqCatalog;

import java.util.Locale;
import java.util.Set;

import static no.sikt.graphitron.model.grammar.DirectiveArgs.argString;

/**
 * The one place in the walk that answers "is this object type a node?", on the terms the store's
 * {@code graphitron_node} states: an object type is a node when its {@code @table} binds exactly one
 * table of the catalog, and it declares {@code @node} or publishes the Relay {@code Node} contract
 * over a table whose backing jOOQ class carries {@code __NODE_TYPE_ID} / {@code __NODE_KEY_COLUMNS}
 * (see {@code docs/manual/reference/directives/node.adoc}). A {@code @node} with no table to take
 * effect on is no node, and the store refuses it as {@code NODE_WITHOUT_TABLE}.
 *
 * <h3>Why this is a named predicate rather than a directive read</h3>
 * Before inference, {@code @node} presence and
 * {@code GraphitronType.NodeType} membership were the same set, so
 * several consumers read the directive straight off SDL and stayed consistent with the classifier
 * by coincidence. Inference splits the two, and each of those consumers is a place an inferred node
 * and an explicit one would otherwise behave differently: reachability seeding
 * ({@code SchemaReachability}), arrival folding ({@code ArrivalIndex}), federation entity membership
 * ({@link no.sikt.graphitron.model.schema.federation.KeyNodeSynthesiser}). They all call this
 * instead.
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
 * <p>{@code @node} without {@code implements Node} reads as a node here, as it does in
 * {@code graphitron_node}. The Relay interface requirement is the store's to enforce, as
 * {@code NODE_WITHOUT_NODE_INTERFACE}, and a graph it refuses is never classified, so neither this
 * predicate nor the classifier has to. A change to the rule belongs in the store and here, and
 * {@code NodeTypeShadowTest} holds the two to the same set.
 *
 * <p>A node this subgraph does not resolve, {@code @node(resolvable: false)}, is a node here like
 * any other. Whether it is resolvable is a separate fact, {@link #isResolvable(GraphQLObjectType)},
 * which a consumer that fetches by id asks beside this.
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

    /** The {@code resolvable} argument, as {@code @node(resolvable:)} spells it. */
    public static final String ARG_RESOLVABLE = "resolvable";


    /** The root operation types, which a {@code @table} binds no table on. */
    private static final Set<String> ROOT_TYPES = Set.of("Query", "Mutation", "Subscription");

    private final JooqCatalog catalog;

    /**
     * @param catalog the jOOQ catalog the bound table resolves in and that is probed for
     *                {@code __NODE_*} metadata, or {@code null} for a context that has none (the
     *                nullable catalog {@code BuildContext} accepts from tests). With no catalog no
     *                table resolves, so no type is a node, as the store answers with no catalog
     *                captured.
     */
    public NodeDeclaration(JooqCatalog catalog) {
        this.catalog = catalog;
    }

    /**
     * Whether {@code obj} is a node type: its {@code @table} binds one table of the catalog, and
     * {@code @node} is declared or nodehood is inferred from {@code implements Node} over a table
     * whose backing class publishes node metadata.
     */
    public boolean isNodeType(GraphQLObjectType obj) {
        if (!obj.hasAppliedDirective(DIR_TABLE) || !bindsATable(obj.getName(), boundTableName(obj))) {
            return false;
        }
        return obj.hasAppliedDirective(DIR_NODE)
            || implementsNode(obj) && hasNodeIdMetadata(boundTableName(obj));
    }

    /**
     * Raw-registry sibling of {@link #isNodeType(GraphQLObjectType)}, for the pre-classification
     * stages that hold {@link ObjectTypeDefinition}s rather than an assembled schema. Same rule,
     * read off the AST.
     */
    public boolean isNodeType(ObjectTypeDefinition obj) {
        if (!hasDirective(obj, DIR_TABLE) || !bindsATable(obj.getName(), boundTableName(obj))) {
            return false;
        }
        return hasDirective(obj, DIR_NODE)
            || implementsNode(obj) && hasNodeIdMetadata(boundTableName(obj));
    }

    /**
     * Whether a type's {@code @table} binds exactly one table, as {@code graphitron_tabletype} binds
     * it: a root operation type binds none, and a name the catalog does not hold, or holds in
     * several schemas, binds none either.
     */
    private boolean bindsATable(String typeName, String tableSqlName) {
        return catalog != null
            && !ROOT_TYPES.contains(typeName)
            && catalog.findTable(tableSqlName) instanceof JooqCatalog.TableResolution.Resolved;
    }

    /**
     * Whether this subgraph resolves {@code obj} by its id: false where its {@code @node} says
     * {@code resolvable: false}, true otherwise. A fact of its own beside nodehood: a node that is
     * not resolvable is a node all the same, and only what fetches by id asks this.
     */
    public static boolean isResolvable(GraphQLObjectType obj) {
        var dir = obj.getAppliedDirective(DIR_NODE);
        var argument = dir == null ? null : dir.getArgument(ARG_RESOLVABLE);
        return !(argument != null && (argument.getValue() instanceof BooleanValue b ? !b.isValue()
            : Boolean.FALSE.equals(argument.getValue())));
    }

    /** Raw-registry sibling of {@link #isResolvable(GraphQLObjectType)}. */
    public static boolean isResolvable(ObjectTypeDefinition obj) {
        return obj.getDirectives().stream()
            .filter(d -> DIR_NODE.equals(d.getName()))
            .findFirst()
            .map(d -> d.getArgument(ARG_RESOLVABLE))
            .filter(a -> a.getValue() instanceof BooleanValue b && !b.isValue())
            .isEmpty();
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
