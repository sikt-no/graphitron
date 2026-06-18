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
 * R279 slice 1 — the reachability observatory. Computes the set of named output types
 * (object / interface / union) reachable from the operation roots plus the federation seed scan,
 * by the same {@link SchemaTraverser} walk the field-first classification driver will be built on
 * in slice 3. This slice only <em>measures</em> reachability; it changes no classification
 * behaviour.
 *
 * <h3>Seeds</h3>
 * Query and Mutation roots, plus every object type carrying an applied {@code @node} or
 * {@code @key} directive. The directive scan is load-bearing: federation entity types
 * ({@code _entities} / {@code _Entity} are injected post-build in
 * {@code GraphitronSchemaClassGenerator}) and a {@code @node} type that no field returns are
 * reachable through no field, so reachability cannot hinge on a {@code Query.node} /
 * {@code Query._entities} field being present. Both directive names are scanned because the
 * {@code @node} → {@code @key} synthesis ({@code KeyNodeSynthesiser}) runs only on the production
 * attributed-registry path, not on every classify; scanning both covers both paths.
 * Subscription is recognised-but-unsupported: it is seeded so the root type classifies (the
 * schema-class generator routes the subscription entry point), but its root fields classify to
 * {@code UnclassifiedField} and reach no supported targets.
 *
 * <h3>Descent edges</h3>
 * The walk follows exactly the output-structure edges, supplied through the
 * {@link SchemaTraverser#SchemaTraverser(Function) custom child function} rather than graphql-java's
 * native {@code getChildren()}: a node descends into its fields' unwrapped output types, a union
 * into its members, and an interface additionally into its implementors
 * ({@link GraphQLSchema#getImplementations(GraphQLInterfaceType)}). The interface → implementor edge
 * is the asymmetry that makes this a custom child function: {@code GraphQLUnionType.getChildren()}
 * surfaces its members, but {@code GraphQLInterfaceType.getChildren()} does <em>not</em> surface its
 * implementors (only the reverse, implementor → interface, exists natively, and never fires here
 * because we do not descend object → interface). Supplying the forward edge per node makes interface
 * reachability a transitive fixpoint discovered at whatever depth the interface first appears.
 *
 * <p>Arguments and input objects are deliberately not descended: classification binds arguments per
 * field-usage, never as standalone traversal events, and no output type is reachable only through an
 * argument position. Scalars and enums are leaves.
 *
 * <p>The returned set includes the operation root type names (Query / Mutation) themselves, since
 * the walk visits them. Callers checking the {@code reachable ⊆ classified} invariant exclude the
 * roots: the classifier classifies a root's <em>fields</em>, not the root <em>type</em>, so an
 * operation root is intentionally absent from {@link GraphitronSchema#types()}.
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
     * R317 slice 4 — the single classify-and-emit walk. Runs {@code visitor} over the same
     * reachable output surface {@link #reachableTypeNames} measures (same seeds, same descent
     * edges), driving classification on enter rather than only collecting a name set. The
     * {@link SchemaTraverser} fires the visitor's {@code visitGraphQL*Type} callbacks on enter
     * exactly once per node identity (graphql-java's {@code Traverser} routes re-encounters to
     * {@code backRef}), so a real visitor classifies each reached composite once, with no dedup of
     * its own. The custom child function supplies the output-structure descent (field-output,
     * union-member, interface-implementor, object/interface {@code implements}); see
     * {@link #reachableTypeNames} for the edge rationale.
     */
    public static void walk(GraphQLSchema schema, GraphQLTypeVisitor visitor) {
        var expanded = new HashSet<GraphQLSchemaElement>();
        Function<GraphQLSchemaElement, List<GraphQLSchemaElement>> children =
            element -> childrenOf(schema, element, expanded);
        new SchemaTraverser(children).depthFirst(visitor, seeds(schema));
    }

    /**
     * The output-structure descent edges, shared by {@link #reachableTypeNames} (which only measures)
     * and {@link #walk} (which classifies on enter). graphql-java schema elements use identity
     * equality, so {@code expanded} dedupes by node identity: once a node has been expanded its
     * children are not re-pushed, which terminates the walk on recursive (cyclic) schema types
     * regardless of the traverser's own visited tracking.
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
                // interfaces even when no field returns the interface — the federation case where
                // a @node / @key implementor is seeded directly and the Node interface itself is
                // returned by no field (it would otherwise be pruned and the implements clause
                // would dangle).
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
