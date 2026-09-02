package no.sikt.graphitron.rewrite;

import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLInputObjectType;
import graphql.schema.GraphQLInterfaceType;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLSchemaElement;
import graphql.schema.GraphQLTypeUtil;
import graphql.schema.GraphQLTypeVisitor;
import graphql.schema.GraphQLTypeVisitorStub;
import graphql.schema.GraphQLUnionType;
import graphql.schema.SchemaTraverser;
import no.sikt.graphitron.model.schema.DeclaredDirectives;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import no.sikt.graphitron.model.grammar.NodeDeclaration;

/**
 * Reachability over the schema's whole declared-and-reached surface. {@link #reachableTypeNames}
 * computes the set of named output types (object / interface / union) reachable from the seeds;
 * {@link #walk} drives a classification visitor over the same traversal.
 *
 * <h3>Seeds</h3>
 * Query, Mutation and Subscription roots, plus every node type ({@link NodeDeclaration}) and every
 * object type carrying an applied {@code @key} directive. The seed scan is load-bearing: federation
 * entity types ({@code _entities} / {@code _Entity} are injected post-build in
 * {@link no.sikt.graphitron.rewrite.generators.schema.GraphitronSchemaClassGenerator}) and a
 * node type that no field returns are reachable through no field, so reachability cannot
 * hinge on a {@code Query.node} / {@code Query._entities} field being present. {@code @key} is
 * scanned separately because the node-to-{@code @key} synthesis
 * ({@link no.sikt.graphitron.model.schema.federation.KeyNodeSynthesiser}) runs only on the
 * production attributed-registry path, not on every classify.
 *
 * <p>Nodehood is asked of {@link NodeDeclaration} rather than read off {@code @node}, so a node
 * inferred from {@code implements Node} plus catalog metadata self-seeds exactly like a declared
 * one. That equality is load-bearing beyond reachability itself:
 * {@code TypeBuilder.validateNodeTypeIdUniqueness} iterates the <em>pruned</em> registry, so a node
 * that failed to seed and is reached through no field would escape the typeId-collision check.
 *
 * <h3>Descent edges</h3>
 * The walk follows the output-structure edges (field output target, union member, interface
 * implementor, object/interface {@code implements}) and the input edges (field argument type,
 * input-object field type), supplied through the
 * {@link SchemaTraverser#SchemaTraverser(Function) custom child function} because graphql-java's
 * native {@code getChildren()} lacks the interface to implementor edge
 * ({@link GraphQLSchema#getImplementations(GraphQLInterfaceType)} supplies it here). The input
 * edges make the classifying visitor reach input objects and the scalars / enums that sit only on
 * argument and input-field coordinates, so the whole surface is classified by one traversal and an
 * unreached leaf is pruned exactly like an unreached output composite. No default-value descent is
 * needed: a default-value literal must conform to its declared type, so the type edges subsume
 * every leaf a default value could reference. Scalars and enums are leaves. Survivor directive
 * definitions additionally seed their argument types (see {@link #seeds}). The recorded
 * <em>set</em> stays output-only ({@link #recordIfNamedType} filters to object / interface /
 * union); the walk, not the observatory, owns the input edges' classification consequences.
 *
 * <p>The returned set includes the operation root type names themselves. Callers checking the
 * {@code reachable ⊆ classified} invariant exclude the roots: the classifier classifies a root's
 * <em>fields</em>, not the root <em>type</em>, so an operation root is intentionally absent from
 * {@link GraphitronSchema#types()}.
 */
public final class SchemaReachability {

    private SchemaReachability() {}

    /**
     * Returns the names of all object / interface / union types reachable from the seeds, in
     * first-encounter order. Introspection types ({@code __*}) are excluded.
     *
     * @param nodes the node predicate the seed scan applies; pass the same instance the classifier
     *              uses so the reachable set and the classified set agree on what a node is
     */
    public static Set<String> reachableTypeNames(GraphQLSchema schema, NodeDeclaration nodes) {
        var reachable = new LinkedHashSet<String>();
        var expanded = new HashSet<GraphQLSchemaElement>();
        Function<GraphQLSchemaElement, List<GraphQLSchemaElement>> children = element -> {
            recordIfNamedType(element, reachable);
            return childrenOf(schema, element, expanded);
        };
        new SchemaTraverser(children).depthFirst(new GraphQLTypeVisitorStub(), seeds(schema, nodes));
        return reachable;
    }

    /**
     * Runs {@code visitor} over the same reachable surface {@link #reachableTypeNames} traverses
     * (same seeds, same descent edges, output and input alike), classifying on enter. The
     * {@link SchemaTraverser} fires the visitor's {@code visitGraphQL*Type} callbacks exactly once
     * per node identity (graphql-java's {@code Traverser} routes re-encounters to {@code backRef}),
     * so the visitor needs no dedup of its own.
     */
    public static void walk(GraphQLSchema schema, NodeDeclaration nodes, GraphQLTypeVisitor visitor) {
        var expanded = new HashSet<GraphQLSchemaElement>();
        Function<GraphQLSchemaElement, List<GraphQLSchemaElement>> children =
            element -> childrenOf(schema, element, expanded);
        new SchemaTraverser(children).depthFirst(visitor, seeds(schema, nodes));
    }

    /**
     * The descent edges, shared by {@link #reachableTypeNames} and {@link #walk}.
     * graphql-java schema elements use identity equality, so {@code expanded} dedupes by node
     * identity: once a node has been expanded its children are not re-pushed, which terminates the
     * walk on recursive (cyclic) schema and input types regardless of the traverser's own visited
     * tracking, and dedups the scalars / enums shared by many coordinates.
     */
    private static List<GraphQLSchemaElement> childrenOf(
            GraphQLSchema schema, GraphQLSchemaElement element, Set<GraphQLSchemaElement> expanded) {
        if (!expanded.add(element)) {
            return List.of();
        }
        return switch (element) {
            case GraphQLObjectType obj -> {
                var kids = fieldTargets(obj.getFieldDefinitions());
                // An object's implemented interfaces are part of its emitted structure (the
                // `implements I` clause references I), so a reachable object reaches its
                // interfaces even when no field returns the interface: the federation case where
                // a @node / @key implementor is seeded directly. Without this edge the interface
                // would be pruned and the implements clause would dangle.
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
            default -> List.of();
        };
    }

    private static void recordIfNamedType(GraphQLSchemaElement element, Set<String> reachable) {
        switch (element) {
            case GraphQLObjectType obj -> addUnlessIntrospection(obj.getName(), reachable);
            case GraphQLInterfaceType iface -> addUnlessIntrospection(iface.getName(), reachable);
            case GraphQLUnionType union -> addUnlessIntrospection(union.getName(), reachable);
            default -> { /* scalars, enums, wrappers, input types: not classified output types */ }
        }
    }

    private static void addUnlessIntrospection(String name, Set<String> reachable) {
        if (!name.startsWith("__")) {
            reachable.add(name);
        }
    }

    /** Each field's unwrapped output target plus each of its arguments' unwrapped types. */
    private static List<GraphQLSchemaElement> fieldTargets(List<GraphQLFieldDefinition> fields) {
        var out = new ArrayList<GraphQLSchemaElement>(fields.size());
        for (var field : fields) {
            out.add(GraphQLTypeUtil.unwrapAll(field.getType()));
            for (var arg : field.getArguments()) {
                out.add(GraphQLTypeUtil.unwrapAll(arg.getType()));
            }
        }
        return out;
    }

    private static Collection<GraphQLSchemaElement> seeds(GraphQLSchema schema, NodeDeclaration nodes) {
        var seeds = new ArrayList<GraphQLSchemaElement>();
        if (schema.getQueryType() != null) {
            seeds.add(schema.getQueryType());
        }
        if (schema.getMutationType() != null) {
            seeds.add(schema.getMutationType());
        }
        // Subscription is recognised-but-unsupported: seeding the root classifies it as a RootType
        // (so the schema-class generator can route the subscription entry point) while its fields
        // still classify to UnclassifiedField and reach no supported targets.
        if (schema.getSubscriptionType() != null) {
            seeds.add(schema.getSubscriptionType());
        }
        for (var type : schema.getAllTypesAsList()) {
            if (type instanceof GraphQLObjectType obj
                    && !obj.getName().startsWith("__")
                    && (nodes.isNodeType(obj)
                        || !obj.getAppliedDirectives("key").isEmpty())) {
                seeds.add(obj);
            }
        }
        // Directive definitions that survive into the emitted schema (everything not declared in
        // graphitron's own directives.graphqls, the same fact
        // no.sikt.graphitron.rewrite.generators.util.SchemaDirectiveRegistry#isSurvivor derives
        // from) are re-declared by the schema-class generator, so their argument types are part
        // of the emitted structure the same way an object's implements clause is: a scalar
        // reachable only through a survivor directive's argument (federation__FieldSet on @key)
        // must classify, or the emitted schema dangles a type reference. Graphitron's build-time
        // directives are excluded: their argument types are the directive support types, whose
        // retention is the published-support-type gate's decision, not reachability's.
        var generatorOnly = DeclaredDirectives.names();
        for (var directive : schema.getDirectives()) {
            if (generatorOnly.contains(directive.getName())) continue;
            for (var arg : directive.getArguments()) {
                seeds.add(GraphQLTypeUtil.unwrapAll(arg.getType()));
            }
        }
        return seeds;
    }
}
