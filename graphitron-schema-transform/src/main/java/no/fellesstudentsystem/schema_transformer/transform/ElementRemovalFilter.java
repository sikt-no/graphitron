package no.fellesstudentsystem.schema_transformer.transform;

import graphql.schema.*;
import graphql.util.TraversalControl;
import graphql.util.TraverserContext;

import java.util.Set;
import java.util.stream.Collectors;

import static graphql.util.TraversalControl.CONTINUE;

public class ElementRemovalFilter implements ModifyingGraphQLTypeVisitor {
    private final GraphQLSchema schema;
    private final Set<String> directivesIndicatingRemoval;
    private final boolean preserveUnreachableTypes;

    public ElementRemovalFilter(GraphQLSchema schema, Set<String> directivesIndicatingRemoval) {
        this(schema, directivesIndicatingRemoval, true);
    }

    public ElementRemovalFilter(GraphQLSchema schema, Set<String> directivesIndicatingRemoval, boolean preserveUnreachableTypes) {
        this.schema = schema;
        this.directivesIndicatingRemoval = directivesIndicatingRemoval;
        this.preserveUnreachableTypes = preserveUnreachableTypes;
    }

    /**
     * @return Schema all the types and fields with the specified directives are removed.
     */
    @Override
    public GraphQLSchema getModifiedGraphQLSchema() {
        var visitor = new GraphQLTypeVisitorStub() {
            @Override
            public TraversalControl visitGraphQLFieldDefinition(GraphQLFieldDefinition node, TraverserContext<GraphQLSchemaElement> context) {
                return shouldRemoveLeaf(node, node.getType()) ? deleteNode(context) : CONTINUE;
            }

            @Override
            public TraversalControl visitGraphQLInputObjectField(GraphQLInputObjectField node, TraverserContext<GraphQLSchemaElement> context) {
                return shouldRemoveLeaf(node, node.getType()) ? deleteNode(context) : CONTINUE;
            }

            @Override
            public TraversalControl visitGraphQLEnumValueDefinition(GraphQLEnumValueDefinition node, TraverserContext<GraphQLSchemaElement> context) {
                return shouldRemoveLeaf(node, null) ? deleteNode(context) : CONTINUE;
            }

            @Override
            public TraversalControl visitGraphQLArgument(GraphQLArgument node, TraverserContext<GraphQLSchemaElement> context) {
                return shouldRemoveLeaf(node, node.getType()) ? deleteNode(context) : CONTINUE;
            }

            @Override
            public TraversalControl visitGraphQLObjectType(GraphQLObjectType node, TraverserContext<GraphQLSchemaElement> context) {
                return shouldRemoveContainer(node) ? deleteNode(context) : CONTINUE;
            }

            @Override
            public TraversalControl visitGraphQLInterfaceType(GraphQLInterfaceType node, TraverserContext<GraphQLSchemaElement> context) {
                return shouldRemoveContainer(node) ? deleteNode(context) : CONTINUE;
            }

            @Override
            public TraversalControl visitGraphQLInputObjectType(GraphQLInputObjectType node, TraverserContext<GraphQLSchemaElement> context) {
                return shouldRemoveContainer(node) ? deleteNode(context) : CONTINUE;
            }

            @Override
            public TraversalControl visitGraphQLEnumType(GraphQLEnumType node, TraverserContext<GraphQLSchemaElement> context) {
                return shouldRemoveContainer(node) ? deleteNode(context) : CONTINUE;
            }

            @Override
            public TraversalControl visitGraphQLUnionType(GraphQLUnionType node, TraverserContext<GraphQLSchemaElement> context) {
                return shouldRemoveContainer(node) ? deleteNode(context) : CONTINUE;
            }
        };
        var result = SchemaTransformer.transformSchema(schema, visitor);
        if (preserveUnreachableTypes) {
            return restorePrunedTypes(result);
        }
        return result;
    }

    /**
     * Re-adds types that were pruned by GraphQL Java's SchemaTransformer solely because they became unreachable,
     * but were not explicitly marked for removal and did not become empty.
     */
    private GraphQLSchema restorePrunedTypes(GraphQLSchema result) {
        var remainingTypeNames = result
                .getAllTypesAsList()
                .stream()
                .map(GraphQLNamedType::getName)
                .collect(Collectors.toSet());
        var prunedTypes = schema
                .getAllTypesAsList()
                .stream()
                .filter(t -> !remainingTypeNames.contains(t.getName()))
                .filter(t -> !(t instanceof GraphQLDirectiveContainer dc) || !shouldBeRemoved(dc))
                .filter(t -> !(t instanceof GraphQLDirectiveContainer dc) || !allChildrenRemoved(dc))
                .collect(Collectors.toSet());
        if (prunedTypes.isEmpty()) {
            return result;
        }
        return result.transform(b -> b.additionalTypes(Set.copyOf(prunedTypes)));
    }

    private boolean shouldRemoveLeaf(GraphQLDirectiveContainer node, GraphQLType type) {
        return shouldBeRemoved(node) || (type != null && typeWillBeRemoved(GraphQLTypeUtil.unwrapAll(type)));
    }

    private boolean shouldRemoveContainer(GraphQLDirectiveContainer node) {
        return shouldBeRemoved(node) || allChildrenRemoved(node);
    }

    private boolean typeWillBeRemoved(GraphQLNamedType type) {
        if (type instanceof GraphQLDirectiveContainer container && shouldBeRemoved(container)) {
            return true;
        }
        if (type instanceof GraphQLUnionType union) {
            return union.getTypes().stream().allMatch(this::typeWillBeRemoved);
        }
        if (type instanceof GraphQLDirectiveContainer container) {
            return allChildrenRemoved(container);
        }
        return false;
    }

    private boolean allChildrenRemoved(GraphQLDirectiveContainer node) {
        if (node instanceof GraphQLFieldsContainer t) {
            return t.getFields().stream().allMatch(this::shouldBeRemoved);
        }
        if (node instanceof GraphQLInputObjectType t) {
            return t.getFields().stream().allMatch(this::shouldBeRemoved);
        }
        if (node instanceof GraphQLEnumType t) {
            return t.getValues().stream().allMatch(this::shouldBeRemoved);
        }
        if (node instanceof GraphQLUnionType t) {
            return t.getTypes().stream().allMatch(this::typeWillBeRemoved);
        }
        return false;
    }

    private boolean shouldBeRemoved(GraphQLDirectiveContainer node) {
        return node.getAppliedDirectives().stream().anyMatch(it -> directivesIndicatingRemoval.contains(it.getName()));
    }
}
