package no.fellesstudentsystem.schema_transformer.transform;

import graphql.schema.*;
import graphql.util.TraversalControl;
import graphql.util.TraverserContext;
import static graphql.util.TraversalControl.CONTINUE;
import static no.sikt.graphql.naming.GraphQLReservedName.*;

/**
 * Removes non-resolvable {@code @key} directives. The {@code @key} directive applies to
 * OBJECT and INTERFACE types only.
 */
public class KeyFilter implements ModifyingGraphQLTypeVisitor {
    private final GraphQLSchema schema;

    public KeyFilter(GraphQLSchema schema) {
        this.schema = schema;
    }

    @Override
    public GraphQLSchema getModifiedGraphQLSchema() {
        var visitor = new GraphQLTypeVisitorStub() {
            @Override
            public TraversalControl visitGraphQLAppliedDirective(GraphQLAppliedDirective node, TraverserContext<GraphQLSchemaElement> context) {
                if (!FEDERATION_KEY.getName().equals(node.getName())) {
                    return CONTINUE;
                }
                return !((boolean) node.getArgument(FEDERATION_KEY_RESOLVABLE.getName()).getValue()) ? deleteNode(context) : CONTINUE;
            }

            @Override
            public TraversalControl visitGraphQLDirective(GraphQLDirective node, TraverserContext<GraphQLSchemaElement> context) {
                if (!FEDERATION_KEY.getName().equals(node.getName())) {
                    return CONTINUE;
                }
                var argument = node.getArgument(FEDERATION_KEY_RESOLVABLE.getName());
                if (argument == null) {
                    return CONTINUE;
                }
                return Boolean.FALSE.equals(GraphQLArgument.getArgumentValue(argument)) ? deleteNode(context) : CONTINUE;
            }
        };
        return SchemaTransformer.transformSchema(schema, visitor);
    }
}
