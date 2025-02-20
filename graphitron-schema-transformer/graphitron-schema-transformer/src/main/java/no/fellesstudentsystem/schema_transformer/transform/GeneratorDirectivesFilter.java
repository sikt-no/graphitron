package no.fellesstudentsystem.schema_transformer.transform;

import graphql.schema.*;
import graphql.util.TraversalControl;
import graphql.util.TraverserContext;
import no.sikt.graphql.directives.GenerationDirective;

import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static graphql.util.TraversalControl.CONTINUE;
import static no.fellesstudentsystem.schema_transformer.mapping.GraphQLDirective.RESOLVER;

public class GeneratorDirectivesFilter implements ModifyingGraphQLTypeVisitor {
    private final GraphQLSchema schema;
    private static final Set<String> excludedDirectiveNames = Stream
            .of(GenerationDirective.values())
            .map(GenerationDirective::getName)
            .filter(it -> !it.equals(RESOLVER.getName()))
            .collect(Collectors.toSet());

    public GeneratorDirectivesFilter(GraphQLSchema schema) {
        this.schema = schema;
    }

    /**
     * @return schema where all the generator specific directives have been removed.
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
    private static boolean isExcludedDirective(String name) {
        return excludedDirectiveNames.contains(name);
    }
}
