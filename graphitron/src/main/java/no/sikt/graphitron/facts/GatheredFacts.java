package no.sikt.graphitron.facts;

import graphql.schema.GraphQLFieldsContainer;
import graphql.schema.GraphQLInputObjectType;
import graphql.schema.GraphQLInterfaceType;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLSchemaElement;
import graphql.schema.GraphQLTypeVisitor;
import graphql.schema.GraphQLTypeVisitorStub;
import graphql.util.TraversalControl;
import graphql.util.TraverserContext;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;

/**
 * The gathered fact relations, one named typed slot per registered visitor's output. The
 * traversal is the shared half of the engine; the sinks are not shared: each visitor owns its
 * typed accumulator, and {@link #gather}'s slot fill switches over {@link FactVisitor}'s sealed
 * permits with no default, so a registered visitor without a slot is a compile error rather
 * than a silently dropped relation.
 *
 * <p>{@code gather} takes the traversal as a function rather than owning one: the caller passes
 * the same reachability walk classification runs on (the pre-rewrite assembled schema's
 * reachable surface), so the fact population and the classified population are one surface by
 * construction, and the reachability logic keeps its single home. The connection carrier
 * rewrite transforms field definitions strictly downstream of both the gather and every
 * classification-time read, which is what makes the relations' definition-identity keying
 * sound.
 */
public record GatheredFacts(PaginationFacts pagination,
                            ConditionFacts condition,
                            OrderByFacts orderBy,
                            LookupFacts lookup,
                            ServiceFacts service,
                            WriteFacts write) {

    public GatheredFacts {
        Objects.requireNonNull(pagination, "pagination");
        Objects.requireNonNull(condition, "condition");
        Objects.requireNonNull(orderBy, "orderBy");
        Objects.requireNonNull(lookup, "lookup");
        Objects.requireNonNull(service, "service");
        Objects.requireNonNull(write, "write");
    }

    /** For harnesses that build no schema; every relation is empty. */
    public static GatheredFacts empty() {
        return new GatheredFacts(new PaginationFacts(Map.of()),
            new ConditionFacts(Map.of(), Map.of()),
            new OrderByFacts(Map.of()),
            new LookupFacts(Map.of(), Map.of()),
            new ServiceFacts(Map.of()),
            new WriteFacts(Map.of()));
    }

    /**
     * Runs the registered visitors over the traversal and lands each relation in its slot.
     *
     * @param schema    the pre-rewrite assembled schema, the same artifact classification reads
     * @param traversal the shared reachability walk, e.g. {@code SchemaReachability::walk}
     */
    public static GatheredFacts gather(GraphQLSchema schema,
                                       BiConsumer<GraphQLSchema, GraphQLTypeVisitor> traversal) {
        var visitors = FactVisitors.builtIn();
        traversal.accept(schema, new Dispatcher(visitors));
        PaginationFacts pagination = null;
        ConditionFacts condition = null;
        OrderByFacts orderBy = null;
        LookupFacts lookup = null;
        ServiceFacts service = null;
        WriteFacts write = null;
        for (var visitor : visitors) {
            switch (visitor) {
                case PaginationFactVisitor p -> pagination = p.relation();
                case ConditionFactVisitor c -> condition = c.relation();
                case OrderByFactVisitor o -> orderBy = o.relation();
                case LookupFactVisitor l -> lookup = l.relation();
                case ServiceFactVisitor s -> service = s.relation();
                case WriteFactVisitor w -> write = w.relation();
            }
        }
        return new GatheredFacts(pagination, condition, orderBy, lookup, service, write);
    }

    /**
     * Adapts the fact-subject dispatch onto the reachability walk's type-visitor callbacks:
     * each reached composite dispatches an {@link FactSubjectKind#OUTPUT_TYPE} subject and one
     * {@link FactSubjectKind#FIELD_COORDINATE} subject per field; each reached input object
     * dispatches one {@link FactSubjectKind#INPUT_OBJECT_FIELD} subject per member. The
     * traversal fires each node once, so relations need no dedup. Introspection types
     * ({@code __}-prefixed) are outside the user surface and dispatch nothing.
     */
    private static final class Dispatcher extends GraphQLTypeVisitorStub {

        private final List<FactVisitor> visitors;

        private Dispatcher(List<FactVisitor> visitors) {
            this.visitors = visitors;
        }

        @Override
        public TraversalControl visitGraphQLObjectType(GraphQLObjectType node,
                TraverserContext<GraphQLSchemaElement> context) {
            dispatchComposite(node);
            return TraversalControl.CONTINUE;
        }

        @Override
        public TraversalControl visitGraphQLInterfaceType(GraphQLInterfaceType node,
                TraverserContext<GraphQLSchemaElement> context) {
            dispatchComposite(node);
            return TraversalControl.CONTINUE;
        }

        @Override
        public TraversalControl visitGraphQLInputObjectType(GraphQLInputObjectType node,
                TraverserContext<GraphQLSchemaElement> context) {
            if (node.getName().startsWith("__")) {
                return TraversalControl.CONTINUE;
            }
            for (var visitor : visitors) {
                if (!visitor.kinds().contains(FactSubjectKind.INPUT_OBJECT_FIELD)) {
                    continue;
                }
                for (var field : node.getFieldDefinitions()) {
                    visitor.visitInputObjectField(node, field);
                }
            }
            return TraversalControl.CONTINUE;
        }

        private void dispatchComposite(GraphQLFieldsContainer node) {
            if (node.getName().startsWith("__")) {
                return;
            }
            for (var visitor : visitors) {
                if (visitor.kinds().contains(FactSubjectKind.OUTPUT_TYPE)) {
                    visitor.visitOutputType(node);
                }
                if (visitor.kinds().contains(FactSubjectKind.FIELD_COORDINATE)) {
                    for (var field : node.getFieldDefinitions()) {
                        visitor.visitFieldCoordinate(node.getName(), field);
                    }
                }
            }
        }
    }
}
