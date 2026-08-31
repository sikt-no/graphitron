package no.sikt.graphitron.rewrite.capture;

import graphql.schema.GraphQLDirective;
import graphql.schema.GraphQLDirectiveContainer;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLImplementingType;
import graphql.schema.GraphQLInputObjectType;
import graphql.schema.GraphQLInterfaceType;
import graphql.schema.GraphQLNamedType;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLSchemaElement;
import graphql.schema.GraphQLType;
import graphql.schema.GraphQLTypeUtil;
import graphql.schema.GraphQLTypeVisitorStub;
import graphql.schema.GraphQLUnionType;
import graphql.schema.SchemaTraverser;
import no.sikt.graphitron.rewrite.schema.RewriteSchemaLoader;
import org.jooq.DSLContext;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static no.sikt.graphitron.model.Tables.INTENT_EXPANDED_TYPE;
import static no.sikt.graphitron.model.Tables.INTENT_TYPE_DOMAIN;
import static org.jooq.impl.DSL.select;
import static org.jooq.impl.DSL.val;

/**
 * The SDL gatherer's rooted traversal: the last stage, writing {@code intent_type_domain} from a
 * depth-first walk of the assembled schema over the seeds the document itself states.
 *
 * <p>The traversal is the producer rather than a closure stated in SQL, because the descent rule is
 * graphql-java's own child semantics: field targets, argument types, input-object fields, union
 * members, and {@code implements} in both directions with the interface-to-implementor edge read
 * off the schema. A SQL closure restates that rule edge kind by edge kind and has to track every
 * SDL feature by hand. What decides the stratum is what a relation's rows are a function of, never
 * which program computes them, and these rows stay a function of the captured sources alone.
 *
 * <h3>The seeds are SDL facts</h3>
 * The operation roots, the {@code @node} carriers, the types that declare {@code implements Node},
 * the authored {@code @key} carriers, and the argument types of every directive definition that
 * survives into the emitted schema. Nothing here reads the catalog, which is what makes this a
 * one-corpus producer and a stage of the SDL gatherer rather than a derivation over two censuses.
 *
 * <p>The three declaration arms scan every {@link GraphQLImplementingType} rather than objects
 * alone, because two of the three declarations are legal on an interface: federation's {@code @key}
 * is defined {@code on OBJECT | INTERFACE}, and an interface may sit in another interface's
 * {@code implements} clause. Narrowing to objects would have made an interface's own declaration
 * seed nothing, which the arms are not about; an object arm reaches its interfaces anyway, so the
 * widening only bites where the interface is the carrier.
 *
 * <p>The node seed is the declaration and not the inference. Inferred nodehood conjoins
 * {@code implements Node} with a {@code @table} binding and well-formed node metadata on the bound
 * table, and both conjuncts the seed drops decide what nodehood <em>means</em>, never whether the
 * author declared it. Seeding is monotone, so the declaration alone yields the superset that
 * answers the membership question correctly: a type declaring the Relay contract is a domain member
 * whether it binds no table at all or a table whose metadata is absent or defective, and so gains
 * diagnostics instead of vanishing. What each member's nodehood amounts to is a question for the
 * readers of the captured node facts, one join away.
 *
 * <p>Survivorship is read from the definition's own source rather than from a name set the
 * generator holds: a directive defined in the bundled {@code directives.graphqls} is build-time
 * vocabulary the emitted schema does not carry, and every other definition is re-declared by the
 * schema-class generator, so its argument types are part of the emitted structure the same way an
 * object's {@code implements} clause is. A definition with no AST pointer is one graphql-java added
 * itself (the specification's own {@code @skip} / {@code @include} / {@code @deprecated} /
 * {@code @specifiedBy} / {@code @oneOf}); those are survivors too, their argument types being spec
 * scalars.
 *
 * <h3>The expansion's own shapes</h3>
 * The assembled schema is the one capture reads, before the pipeline's synthesis rewrites, so it
 * does not carry the {@code @asConnection} expansion's minted types even though the store does. The
 * expansion states its own edges ({@link MacroCapture#synthesizedEdges()}) and the traversal
 * follows them by name, resolving any name the schema does know back into a traversal seed, so a
 * minted Connection reached from a domain member is a member exactly as an authored type would be.
 *
 * <h3>Availability</h3>
 * A run whose registry did not assemble has no schema to traverse and writes no rows, which is why
 * this relation's absence is read together with the assembly verdict rather than alone. The
 * relation's own readers are the demand reductions and the build-error population of the
 * authored-claim conflict detection, and the latter only runs on a classified pass, which an
 * assembly refusal has already ruled out.
 */
final class ClassificationDomainCapture {

    /** The Relay interface an inferred node declares; the seed's whole SDL condition. */
    private static final String NODE_INTERFACE = "Node";

    /** The directive an author writes to declare nodehood outright. */
    private static final String DIR_NODE = "node";

    /** Federation's entity key; an authored carrier seeds on its own arm. */
    private static final String DIR_KEY = "key";

    /** graphql-java's introspection namespace, which no author declares and capture never holds. */
    private static final String INTROSPECTION_PREFIX = "__";

    private ClassificationDomainCapture() {}

    /**
     * Clears {@code graphName}'s domain partition and rewrites it from the traversal. Runs inside
     * the capture transaction after the flush, so the rows are current exactly when the census they
     * are total over is.
     *
     * @param synthesizedEdges the capture-side expansion's own edges, source type name to the type
     *                         names its synthesized members reference
     */
    static void derive(DSLContext dsl, String graphName, GraphQLSchema schema,
                       Map<String, Set<String>> synthesizedEdges) {
        dsl.deleteFrom(INTENT_TYPE_DOMAIN)
            .where(INTENT_TYPE_DOMAIN.GRAPH_NAME.eq(graphName))
            .execute();
        if (schema == null) {
            return;
        }
        Set<String> reached = reach(schema, synthesizedEdges);
        if (reached.isEmpty()) {
            return;
        }
        // Every reached name the census holds. The intersection is the FK made structural rather
        // than checked: a name graphql-java added itself, or one the expansion states over a type
        // no document declares, is simply not a row.
        dsl.insertInto(INTENT_TYPE_DOMAIN, INTENT_TYPE_DOMAIN.GRAPH_NAME, INTENT_TYPE_DOMAIN.TYPE_NAME)
            .select(select(val(graphName), INTENT_EXPANDED_TYPE.TYPE_NAME)
                .from(INTENT_EXPANDED_TYPE)
                .where(INTENT_EXPANDED_TYPE.GRAPH_NAME.eq(graphName))
                .and(INTENT_EXPANDED_TYPE.TYPE_NAME.in(reached)))
            .execute();
    }

    /**
     * The names the traversal reaches, of every kind. Runs to a fixpoint over two alternating
     * moves: the schema traversal from a seed set, and the expansion's name edges over what it
     * reached. A name the expansion adds that the schema does know re-enters as a seed, so an
     * author-declared type the expansion happens to name is descended into rather than recorded
     * bare.
     */
    private static Set<String> reach(GraphQLSchema schema, Map<String, Set<String>> synthesizedEdges) {
        var reached = new LinkedHashSet<String>();
        var expanded = new HashSet<GraphQLSchemaElement>();
        var seeded = new HashSet<String>();
        Function<GraphQLSchemaElement, List<GraphQLSchemaElement>> children = element -> {
            record(element, reached);
            return childrenOf(schema, element, expanded);
        };
        var traverser = new SchemaTraverser(children);
        Collection<GraphQLSchemaElement> frontier = seeds(schema);
        while (!frontier.isEmpty()) {
            traverser.depthFirst(new GraphQLTypeVisitorStub(), frontier);
            frontier = expansionFrontier(schema, synthesizedEdges, reached, seeded);
        }
        return reached;
    }

    /**
     * One round of the expansion's name closure: every name its edges add that the traversal has
     * not recorded, recorded here, and the subset of those the schema can resolve handed back as
     * the next traversal's seeds.
     */
    private static Collection<GraphQLSchemaElement> expansionFrontier(
            GraphQLSchema schema, Map<String, Set<String>> synthesizedEdges,
            Set<String> reached, Set<String> seeded) {
        var next = new ArrayList<GraphQLSchemaElement>();
        var pending = new ArrayList<>(reached);
        while (!pending.isEmpty()) {
            var round = new ArrayList<String>();
            for (String name : pending) {
                for (String target : synthesizedEdges.getOrDefault(name, Set.of())) {
                    GraphQLType known = schema.getType(target);
                    if (known instanceof GraphQLSchemaElement element && seeded.add(target)) {
                        next.add(element);
                        continue;
                    }
                    if (known == null && reached.add(target)) {
                        round.add(target);
                    }
                }
            }
            pending = round;
        }
        return next;
    }

    /**
     * The descent edges: graphql-java's child semantics, with the interface-to-implementor edge
     * supplied from the schema because {@code getChildren()} lacks it. Schema elements use identity
     * equality, so {@code expanded} terminates the walk on the cyclic type graphs legal GraphQL
     * allows and dedupes the leaves many coordinates share.
     */
    private static List<GraphQLSchemaElement> childrenOf(
            GraphQLSchema schema, GraphQLSchemaElement element, Set<GraphQLSchemaElement> expanded) {
        if (!expanded.add(element)) {
            return List.of();
        }
        return switch (element) {
            case GraphQLObjectType obj -> {
                var kids = fieldTargets(obj.getFieldDefinitions());
                // An object's implemented interfaces are part of its emitted structure: the
                // implements clause references the interface whether or not any field returns it.
                kids.addAll(obj.getInterfaces());
                yield kids;
            }
            case GraphQLInterfaceType iface -> {
                var kids = fieldTargets(iface.getFieldDefinitions());
                kids.addAll(iface.getInterfaces());
                kids.addAll(schema.getImplementations(iface));
                yield kids;
            }
            case GraphQLUnionType union -> new ArrayList<>(union.getTypes());
            case GraphQLInputObjectType input -> {
                var kids = new ArrayList<GraphQLSchemaElement>(input.getFieldDefinitions().size());
                for (var field : input.getFieldDefinitions()) {
                    kids.add(GraphQLTypeUtil.unwrapAll(field.getType()));
                }
                yield kids;
            }
            // Scalars and enums are leaves. A default-value literal needs no edge of its own: it
            // must conform to its declared type, so the type edges subsume every leaf one could
            // reference.
            default -> List.of();
        };
    }

    /** Each field's unwrapped output target plus each of its arguments' unwrapped types. */
    private static List<GraphQLSchemaElement> fieldTargets(List<GraphQLFieldDefinition> fields) {
        var out = new ArrayList<GraphQLSchemaElement>(fields.size());
        for (var field : fields) {
            out.add(GraphQLTypeUtil.unwrapAll(field.getType()));
            for (var argument : field.getArguments()) {
                out.add(GraphQLTypeUtil.unwrapAll(argument.getType()));
            }
        }
        return out;
    }

    /** Records a named type of any kind; the introspection namespace is nobody's declaration. */
    private static void record(GraphQLSchemaElement element, Set<String> reached) {
        if (element instanceof GraphQLNamedType named && !isIntrospection(named.getName())) {
            reached.add(named.getName());
        }
    }

    private static boolean isIntrospection(String name) {
        return name.startsWith(INTROSPECTION_PREFIX);
    }

    /** The SDL-stated seeds; see the class javadoc for what each arm is owed to. */
    private static Collection<GraphQLSchemaElement> seeds(GraphQLSchema schema) {
        var seeds = new ArrayList<GraphQLSchemaElement>();
        addIfPresent(seeds, schema.getQueryType());
        addIfPresent(seeds, schema.getMutationType());
        // Subscription is recognised-but-unsupported, and seeding its root is what lets the
        // generator route the entry point at all; its fields reach no supported target.
        addIfPresent(seeds, schema.getSubscriptionType());
        for (var type : schema.getAllTypesAsList()) {
            if (!isIntrospection(type.getName()) && carriesASeedDeclaration(type)) {
                seeds.add(type);
            }
        }
        for (GraphQLDirective directive : schema.getDirectives()) {
            if (!survives(directive)) {
                continue;
            }
            for (var argument : directive.getArguments()) {
                seeds.add(GraphQLTypeUtil.unwrapAll(argument.getType()));
            }
        }
        return seeds;
    }

    private static void addIfPresent(List<GraphQLSchemaElement> seeds, GraphQLObjectType root) {
        if (root != null) {
            seeds.add(root);
        }
    }

    /**
     * Whether the type carries one of the three declarations that seed on their own arm. Both kinds
     * that can carry one qualify, which is why the test is the pair of capabilities rather than a
     * concrete kind: applying a directive and having an {@code implements} clause.
     */
    private static boolean carriesASeedDeclaration(GraphQLNamedType type) {
        if (!(type instanceof GraphQLImplementingType carrier)
                || !(type instanceof GraphQLDirectiveContainer applied)) {
            return false;
        }
        return applied.hasAppliedDirective(DIR_NODE)
            || applied.hasAppliedDirective(DIR_KEY)
            || declaresNodeContract(carrier);
    }

    /** Whether the type declares the Relay {@code Node} contract in its {@code implements} clause. */
    private static boolean declaresNodeContract(GraphQLImplementingType carrier) {
        return carrier.getInterfaces().stream()
            .anyMatch(i -> i instanceof GraphQLNamedType named && NODE_INTERFACE.equals(named.getName()));
    }

    /**
     * Whether a directive definition reaches the emitted schema, read off the definition's own
     * source: graphitron's bundled vocabulary is build-time only, everything else is re-declared.
     */
    private static boolean survives(GraphQLDirective directive) {
        var definition = directive.getDefinition();
        if (definition == null || definition.getSourceLocation() == null) {
            return true;
        }
        return !RewriteSchemaLoader.DIRECTIVES_SOURCE_NAME
            .equals(definition.getSourceLocation().getSourceName());
    }
}
