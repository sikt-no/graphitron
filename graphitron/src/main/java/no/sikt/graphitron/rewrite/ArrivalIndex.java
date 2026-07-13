package no.sikt.graphitron.rewrite;

import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLInterfaceType;
import graphql.schema.GraphQLNamedType;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLType;
import graphql.schema.GraphQLTypeUtil;
import graphql.schema.GraphQLUnionType;
import no.sikt.graphitron.rewrite.model.Arrival;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * R463 — the ancestor-product arrival fold, materialised as a pure, typename-keyed index over the
 * assembled SDL. A composite type's {@link Arrival} is how many source objects of it reach a nested
 * field's fetcher in one request; {@link GraphitronSchema#sourceOf} threads the parent type's arrival
 * into {@link no.sikt.graphitron.rewrite.model.OutputField#source(Arrival)} to pick the
 * {@code OnlyChild} / {@code Child} arm.
 *
 * <h3>The fold</h3>
 * Arrival is the lattice {@code One < Many} with {@code Many} absorbing (the wrapper-algebra monoid,
 * R316: {@code Root} the empty product, {@code OnlyChild} the identity, {@code Child} the absorber).
 * For a composite type {@code T}:
 * <ul>
 *   <li><b>Many</b> if {@code T} carries a {@code @node} / {@code @key} seed (node and entity lookups
 *       arrive batched), or if more than one field edge reaches {@code T} through the structural
 *       closure (two coordinates can co-materialize {@code T} instances in one request). The
 *       multi-edge rule subsumes recursion — a reachable cycle implies a second reaching edge — so no
 *       fixed point is needed; the {@code One} verdicts form an acyclic tree hanging off the operation
 *       roots.</li>
 *   <li>else, with exactly one reaching field edge {@code (P, f)}: {@code arrival(P)} tensored with
 *       {@code wrapper(f)}, where {@code wrapper(f)} contributes {@code Many} for any list wrapper and
 *       {@code One} otherwise, and the arrival of a root operation type is the empty product
 *       ({@code One}).</li>
 * </ul>
 *
 * <h3>Edges</h3>
 * The edge set is the same one {@link SchemaReachability} walks, read off the assembled (pre-connection-
 * promotion) schema so list-ness is the raw SDL fact: {@code @asConnection}'s pre-promotion field is a
 * list ({@code Many}), and a native Relay connection's many-ness arrives through the connection type's
 * {@code edges} list edge rather than the (single) connection field. A field edge is an <em>object</em>
 * type's field reaching a composite type (interface parents emit no edges — their concrete implementors
 * carry the real producers); the reach then propagates through the structural closure (interface →
 * implementors, union → members), which names the same instances, not additional ones, so arrival flows
 * through it unchanged while still counting toward the multi-edge test.
 *
 * <p>Pure, no classification duty and no graphql-java types survive into the stored value (parse-boundary
 * containment): built once from the SDL before any consumer reads it. {@link #EMPTY} answers every
 * lookup with the conservative absorbing {@link Arrival#MANY} for test-constructed schemas that carry no
 * index.
 */
record ArrivalIndex(Map<String, Arrival> byName) {

    /** The conservative empty index: every lookup folds to the absorbing {@link Arrival#MANY}. */
    static final ArrivalIndex EMPTY = new ArrivalIndex(Map.of());

    ArrivalIndex {
        byName = Map.copyOf(byName);
    }

    /**
     * The arrival of the composite type {@code typeName}, defaulting to the absorbing
     * {@link Arrival#MANY} when the type is absent (an unreached type, or the {@link #EMPTY} index).
     * {@code Many} is the always-correct absorber, so a missing entry can never mint a spurious
     * {@code OnlyChild}.
     */
    Arrival of(String typeName) {
        return byName.getOrDefault(typeName, Arrival.MANY);
    }

    /**
     * Folds arrival for every object and interface type in {@code schema} (the types that can be a
     * field's parent). Computed from the assembled SDL alone; see the class javadoc for the fold.
     */
    static ArrivalIndex compute(GraphQLSchema schema) {
        var fold = new Fold(schema);
        var byName = new LinkedHashMap<String, Arrival>();
        for (var type : schema.getAllTypesAsList()) {
            if (type.getName().startsWith("__")) continue;
            if (type instanceof GraphQLObjectType || type instanceof GraphQLInterfaceType) {
                byName.put(type.getName(), fold.arrivalOf(type.getName()));
            }
        }
        return new ArrivalIndex(byName);
    }

    /** One reaching field edge: the declaring parent type and whether the field's wrapper is a list. */
    private record Edge(String parentTypeName, boolean listWrapper) {}

    /**
     * The stateful fold over one schema: builds the reaching-edge map once, then resolves arrivals
     * with memoisation. A cycle (which the fold's own multi-edge rule already resolves to {@code Many})
     * is guarded defensively so recursion terminates regardless.
     */
    private static final class Fold {
        private final GraphQLSchema schema;
        private final Set<String> roots = new HashSet<>();
        private final Set<String> seeds = new HashSet<>();
        private final Map<String, List<Edge>> reachingEdges = new HashMap<>();
        private final Map<String, Arrival> memo = new HashMap<>();
        private final Set<String> inProgress = new HashSet<>();
        private final Map<String, Set<String>> closureCache = new HashMap<>();

        Fold(GraphQLSchema schema) {
            this.schema = schema;
            recordRoot(schema.getQueryType());
            recordRoot(schema.getMutationType());
            recordRoot(schema.getSubscriptionType());
            buildEdges();
        }

        private void recordRoot(GraphQLObjectType root) {
            if (root != null) roots.add(root.getName());
        }

        private void buildEdges() {
            for (var type : schema.getAllTypesAsList()) {
                if (type.getName().startsWith("__")) continue;
                // @node / @key seeds arrive batched; only object types carry these directives.
                if (type instanceof GraphQLObjectType obj
                        && (!obj.getAppliedDirectives("node").isEmpty()
                            || !obj.getAppliedDirectives("key").isEmpty())) {
                    seeds.add(obj.getName());
                }
                // Field edges are emitted by concrete object types (the real producers); an interface's
                // abstract fields are materialised by its implementors, whose own edges are counted.
                if (!(type instanceof GraphQLObjectType obj)) continue;
                for (GraphQLFieldDefinition field : obj.getFieldDefinitions()) {
                    var target = GraphQLTypeUtil.unwrapAll(field.getType());
                    if (!isComposite(target)) continue;
                    var edge = new Edge(obj.getName(), isListWrapped(field.getType()));
                    for (String reached : structuralClosure(((GraphQLNamedType) target).getName())) {
                        reachingEdges.computeIfAbsent(reached, k -> new ArrayList<>()).add(edge);
                    }
                }
            }
        }

        Arrival arrivalOf(String typeName) {
            var cached = memo.get(typeName);
            if (cached != null) return cached;
            // A reachable cycle implies a second reaching edge (handled by the multi-edge rule below),
            // so this guard should never decide the verdict; it terminates recursion defensively.
            if (!inProgress.add(typeName)) return Arrival.MANY;

            Arrival result;
            if (seeds.contains(typeName)) {
                result = Arrival.MANY;
            } else {
                var edges = reachingEdges.getOrDefault(typeName, List.of());
                if (edges.size() >= 2) {
                    result = Arrival.MANY;
                } else if (edges.isEmpty()) {
                    // Operation roots and unreached types: the empty product.
                    result = Arrival.ONE;
                } else {
                    Edge only = edges.get(0);
                    Arrival parentArrival = roots.contains(only.parentTypeName())
                        ? Arrival.ONE
                        : arrivalOf(only.parentTypeName());
                    result = parentArrival.tensor(only.listWrapper() ? Arrival.MANY : Arrival.ONE);
                }
            }
            inProgress.remove(typeName);
            memo.put(typeName, result);
            return result;
        }

        /**
         * The types a field of static type {@code startTypeName} can materialize: the type itself plus,
         * for an abstract type, its implementors / members (transitively). These structural hops name
         * the same instances, so arrival propagates through them unchanged; they still transmit reach
         * for the multi-edge count.
         */
        private Set<String> structuralClosure(String startTypeName) {
            var cached = closureCache.get(startTypeName);
            if (cached != null) return cached;
            var closure = new HashSet<String>();
            var queue = new ArrayDeque<String>();
            queue.add(startTypeName);
            while (!queue.isEmpty()) {
                String name = queue.poll();
                if (!closure.add(name)) continue;
                switch (schema.getType(name)) {
                    case GraphQLInterfaceType iface -> {
                        for (var impl : schema.getImplementations(iface)) {
                            queue.add(impl.getName());
                        }
                    }
                    case GraphQLUnionType union -> {
                        for (var member : union.getTypes()) {
                            queue.add(member.getName());
                        }
                    }
                    default -> { /* object / scalar / enum: no further structural descent */ }
                }
            }
            closureCache.put(startTypeName, closure);
            return closure;
        }

        private static boolean isComposite(GraphQLType type) {
            return type instanceof GraphQLObjectType
                || type instanceof GraphQLInterfaceType
                || type instanceof GraphQLUnionType;
        }

        /** Whether the raw SDL wrapper is a list (a leading non-null is stripped first). */
        private static boolean isListWrapped(GraphQLType type) {
            return GraphQLTypeUtil.unwrapNonNull(type) instanceof graphql.schema.GraphQLList;
        }
    }
}
