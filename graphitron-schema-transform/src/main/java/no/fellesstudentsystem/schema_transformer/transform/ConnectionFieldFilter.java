package no.fellesstudentsystem.schema_transformer.transform;

import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLFieldsContainer;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLSchemaElement;
import graphql.schema.GraphQLTypeVisitorStub;
import graphql.schema.SchemaTransformer;
import graphql.util.TraversalControl;
import graphql.util.TraverserContext;

import java.util.Set;

import static graphql.util.TraversalControl.CONTINUE;

public class ConnectionFieldFilter implements ModifyingGraphQLTypeVisitor {
    private final GraphQLSchema schema;
    private final Set<String> disabledFieldNames;

    public ConnectionFieldFilter(GraphQLSchema schema, Set<String> disabledFieldNames) {
        this.schema = schema;
        this.disabledFieldNames = disabledFieldNames;
    }

    @Override
    public GraphQLSchema getModifiedGraphQLSchema() {
        GraphQLTypeVisitorStub visitor = new GraphQLTypeVisitorStub() {
            @Override
            public TraversalControl visitGraphQLFieldDefinition(
                    GraphQLFieldDefinition node,
                    TraverserContext<GraphQLSchemaElement> context
            ) {
                GraphQLSchemaElement parent = context.getParentNode();

                if (parent instanceof GraphQLFieldsContainer fieldsContainer) {
                    if (fieldsContainer.getName().endsWith("Connection") &&
                        disabledFieldNames.contains(node.getName())) {
                        return deleteNode(context);
                    }
                }

                return CONTINUE;
            }
        };

        return SchemaTransformer.transformSchema(schema, visitor);
    }
}
