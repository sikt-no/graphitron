package no.fellesstudentsystem.schema_transformer.transform;

import graphql.schema.*;
import graphql.util.TraversalControl;
import graphql.util.TraverserContext;

import java.util.Set;

import static graphql.util.TraversalControl.CONTINUE;

public class DirectivesFilter implements ModifyingGraphQLTypeVisitor {
    private final GraphQLSchema schema;
    private final Set<String> directiveNamesToRemove;

    public DirectivesFilter(GraphQLSchema schema, Set<String> directiveNamesToRemove) {
        this.schema = schema;
        this.directiveNamesToRemove = directiveNamesToRemove;
    }

    /**
     * @return Schema where all the specified directives have been removed.
     */
    @Override
    public GraphQLSchema getModifiedGraphQLSchema() {
        var generatorDirectiveVisitor = new GraphQLTypeVisitorStub() {
            @Override
            public TraversalControl visitGraphQLDirective(GraphQLDirective node, TraverserContext<GraphQLSchemaElement> context) {
                if (isExcludedDirective(node.getName())) {
                    return deleteNode(context);
                }
                return CONTINUE;
            }

            @Override
            public TraversalControl visitGraphQLAppliedDirective(GraphQLAppliedDirective node, TraverserContext<GraphQLSchemaElement> context) {
                if (isExcludedDirective(node.getName())) {
                    return deleteNode(context);
                }
                return CONTINUE;
            }
        };
        return SchemaTransformer.transformSchema(schema, generatorDirectiveVisitor);
    }

    /**
     * @return Is this directive to be used only in internal applications?
     */
    private boolean isExcludedDirective(String name) {
        return directiveNamesToRemove.contains(name);
    }
}
