package no.sikt.graphitron.rewrite;

import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLInterfaceType;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLSchemaElement;
import graphql.schema.GraphQLTypeUtil;
import graphql.schema.GraphQLTypeVisitor;
import graphql.schema.GraphQLTypeVisitorStub;
import graphql.schema.GraphQLUnionType;
import graphql.schema.SchemaTraverser;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * Reachability over the schema's output structure. {@link #reachableTypeNames} computes the set of
 * named output types (object / interface / union) reachable from the seeds; {@link #walk} drives a
 * classification visitor over the same surface.
 *
 * <h3>Seeds</h3>
 * Query, Mutation and Subscription roots, plus every object type carrying an applied {@code @node}
 * or {@code @key} directive. The directive scan is load-bearing: federation entity types
 * ({@code _entities} / {@code _Entity} are injected post-build in
 * {@link no.sikt.graphitron.rewrite.generators.schema.GraphitronSchemaClassGenerator}) and a
 * {@code @node} type that no field returns are reachable through no field, so reachability cannot
 * hinge on a {@code Query.node} / {@code Query._entities} field being present. Both directive
 * names are scanned because the {@code @node} to {@code @key} synthesis
 * ({@link no.sikt.graphitron.rewrite.schema.federation.KeyNodeSynthesiser}) runs only on the
 * production attributed-registry path, not on every classify.
 *
 * <h3>Descent edges</h3>
 * The walk follows only output-structure edges, supplied through the
 * {@link SchemaTraverser#SchemaTraverser(Function) custom child function} because graphql-java's
 * native {@code getChildren()} lacks the interface to implementor edge
 * ({@link GraphQLSchema#getImplementations(GraphQLInterfaceType)} supplies it here). Arguments and
 * input objects are deliberately not descended: classification binds arguments per field-usage,
 * never as standalone traversal events, and no output type is reachable only through an argument
 * position. Scalars and enums are leaves.
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
     */
    public static Set<String> reachableTypeNames(GraphQLSchema schema) {
        var reachable = new LinkedHashSet<String>();
        var expanded = new HashSet<GraphQLSchemaElement>();
        Function<GraphQLSchemaElement, List<GraphQLSchemaElement>> children = element -> {
            recordIfNamedType(element, reachable);
            return childrenOf(schema, element, expanded);
        };
        new SchemaTraverser(children).depthFirst(new GraphQLTypeVisitorStub(), seeds(schema));
        return reachable;
    }

    /**
     * Runs {@code visitor} over the same reachable output surface {@link #reachableTypeNames}
     * measures (same seeds, same descent edges), classifying on enter. The {@link SchemaTraverser}
     * fires the visitor's {@code visitGraphQL*Type} callbacks exactly once per node identity
     * (graphql-java's {@code Traverser} routes re-encounters to {@code backRef}), so the visitor
     * needs no dedup of its own.
     */
    public static void walk(GraphQLSchema schema, GraphQLTypeVisitor visitor) {
        var expanded = new HashSet<GraphQLSchemaElement>();
        Function<GraphQLSchemaElement, List<GraphQLSchemaElement>> children =
            element -> childrenOf(schema, element, expanded);
        new SchemaTraverser(children).depthFirst(visitor, seeds(schema));
    }

    /**
     * The output-structure descent edges, shared by {@link #reachableTypeNames} and {@link #walk}.
     * graphql-java schema elements use identity equality, so {@code expanded} dedupes by node
     * identity: once a node has been expanded its children are not re-pushed, which terminates the
     * walk on recursive (cyclic) schema types regardless of the traverser's own visited tracking.
     */
    private static List<GraphQLSchemaElement> childrenOf(
            GraphQLSchema schema, GraphQLSchemaElement element, Set<GraphQLSchemaElement> expanded) {
        if (!expanded.add(element)) {
            return List.of();
        }
        return switch (element) {
            case GraphQLObjectType obj -> {
                var kids = outputTargets(obj.getFieldDefinitions());
                // An object's implemented interfaces are part of its emitted structure (the
                // `implements I` clause references I), so a reachable object reaches its
                // interfaces even when no field returns the interface: the federation case where
                // a @node / @key implementor is seeded directly. Without this edge the interface
                // would be pruned and the implements clause would dangle.
                kids.addAll(obj.getInterfaces());
                yield kids;
            }
            case GraphQLInterfaceType iface -> {
                var kids = outputTargets(iface.getFieldDefinitions());
                kids.addAll(iface.getInterfaces());
                kids.addAll(schema.getImplementations(iface));
                yield kids;
            }
            case GraphQLUnionType union -> new ArrayList<>(union.getTypes());
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

    private static List<GraphQLSchemaElement> outputTargets(List<GraphQLFieldDefinition> fields) {
        var out = new ArrayList<GraphQLSchemaElement>(fields.size());
        for (var field : fields) {
            out.add(GraphQLTypeUtil.unwrapAll(field.getType()));
        }
        return out;
    }

    private static Collection<GraphQLSchemaElement> seeds(GraphQLSchema schema) {
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
                    && (!obj.getAppliedDirectives("node").isEmpty()
                        || !obj.getAppliedDirectives("key").isEmpty())) {
                seeds.add(obj);
            }
        }
        return seeds;
    }
}
