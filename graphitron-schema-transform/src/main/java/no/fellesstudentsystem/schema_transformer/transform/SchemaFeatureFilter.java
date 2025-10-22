package no.fellesstudentsystem.schema_transformer.transform;

import graphql.language.DirectivesContainer;
import graphql.language.UnionTypeDefinition;
import graphql.schema.*;
import graphql.util.TraversalControl;
import graphql.util.TraverserContext;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static graphql.util.TraversalControl.CONTINUE;
import static no.fellesstudentsystem.schema_transformer.mapping.GraphQLDirective.FEATURE;
import static no.fellesstudentsystem.schema_transformer.mapping.GraphQLDirectiveParam.FLAGS;
import static no.fellesstudentsystem.schema_transformer.schema.SchemaHelpers.getOptionalDirectiveArgumentStringList;

/**
 * Filter based on whether feature directives are set in the schema and the flags that are requested.
 */
public class SchemaFeatureFilter {
    private final Set<String> featureFlags;

    public SchemaFeatureFilter() {
        this.featureFlags = Set.of();
    }

    /**
     * @param featureFlags The flags that have been set and should be used for filtering.
     */
    public SchemaFeatureFilter(Set<String> featureFlags) {
        this.featureFlags = featureFlags;
    }

    /**
     * @param schema The schema to be filtered.
     * @return Schema where all fields with flags that are not set for this filter are removed. Objects that can no longer be reached are also removed.
     */
    public GraphQLSchema getFilteredGraphQLSchema(GraphQLSchema schema) {
        var visitorFields = new GraphQLTypeVisitorStub() {
            @Override
            public TraversalControl visitGraphQLFieldDefinition(GraphQLFieldDefinition node, TraverserContext<GraphQLSchemaElement> context) { // Includes interface fields.
                return visitFieldElement(node, context);
            }

            @Override
            public TraversalControl visitGraphQLInputObjectField(GraphQLInputObjectField node, TraverserContext<GraphQLSchemaElement> context) {
                return visitFieldElement(node, context);
            }

            @Override
            public TraversalControl visitGraphQLEnumValueDefinition(GraphQLEnumValueDefinition node, TraverserContext<GraphQLSchemaElement> context) {
                return visitFieldElement(node, context);
            }

            @Override
            public TraversalControl visitGraphQLInputObjectType(GraphQLInputObjectType node, TraverserContext<GraphQLSchemaElement> context) {
                if (containerIsEmpty(node)) {
                    return deleteNode(context);
                }

                return CONTINUE;
            }

            @Override
            public TraversalControl visitGraphQLArgument(GraphQLArgument node, TraverserContext<GraphQLSchemaElement> context) {
                return visitFieldElement(node, context);
            }

            @Override
            public TraversalControl visitGraphQLUnionType(GraphQLUnionType node, TraverserContext<GraphQLSchemaElement> context) {
                var nodeIsEmpty = node.getTypes().stream()
                        .allMatch(typ -> {
                            if (typ instanceof GraphQLFieldsContainer) {
                                var obj = (GraphQLObjectType) typ;
                                return containerIsEmpty(obj);
                            }

                            return false;
                        });

                if (nodeIsEmpty) {
                    return deleteNode(context);
                }

                return CONTINUE;
            }

            @Override
            public TraversalControl visitGraphQLObjectType(GraphQLObjectType node, TraverserContext<GraphQLSchemaElement> context) {
                return visitObjectElement(node, context);
            }

            @Override
            public TraversalControl visitGraphQLInterfaceType(GraphQLInterfaceType node, TraverserContext<GraphQLSchemaElement> context) {
                return visitObjectElement(node, context);
            }

            @Override
            public TraversalControl visitGraphQLEnumType(GraphQLEnumType node, TraverserContext<GraphQLSchemaElement> context) {
                if (containerIsEmpty(node)) {
                    return deleteNode(context);
                }

                return CONTINUE;
            }

            private TraversalControl visitObjectElement(GraphQLFieldsContainer node, TraverserContext<GraphQLSchemaElement> context) {
                if (containerIsEmpty(node)) {
                    return deleteNode(context);
                }
                return CONTINUE;
            }

            private TraversalControl visitFieldElement(GraphQLNamedSchemaElement node, TraverserContext<GraphQLSchemaElement> context) {
                if (assertFeatureFlagsMatch((DirectivesContainer<?>) node.getDefinition())) {
                    return CONTINUE;
                }
                return deleteNode(context);
            }
        };

        // Make sure we always visit every type
        var schemaCopy = schema;
        schema = schema.transform(builder -> {
            for (var type : schemaCopy.getTypeMap().values()) {
                if (type != schemaCopy.getQueryType() && type != schemaCopy.getMutationType() && type != schemaCopy.getSubscriptionType()) {
                    builder.additionalType(type);
                }
            }
        });

        var prunedSchema = SchemaTransformer.transformSchema(schema, visitorFields); // Seems the objects get removed automatically when no fields point to them.
        return removeUnreachableTypes(prunedSchema);
    }

    private GraphQLSchema removeUnreachableTypes(GraphQLSchema schema) {
        var reachableTypes = findReachableTypes(schema);
        return schema.transform(builder -> builder.clearAdditionalTypes().additionalTypes(reachableTypes));
    }

    private Set<GraphQLType> findReachableTypes(GraphQLSchema schema) {
        Set<String> reachableNames = new HashSet<>();
        Set<GraphQLType> reachableTypes = new HashSet<>();

        var schemaRoots = Stream.of(schema.getQueryType(), schema.getMutationType(), schema.getSubscriptionType())
                .filter(Objects::nonNull).toList();
        Queue<GraphQLType> toVisit = new LinkedList<>(schemaRoots);

        while (!toVisit.isEmpty()) {
            GraphQLType type = GraphQLTypeUtil.unwrapAll(toVisit.poll());
            if (type instanceof GraphQLNamedType namedType) {
                var alreadyVisited = reachableNames.add(namedType.getName());
                if (alreadyVisited) {
                    continue;
                }

                reachableTypes.add(namedType);

                var children = schema.getTypeMap().get(namedType.getName()).getChildren();
                for (var child : children) {
                    if (child instanceof GraphQLNamedType namedChild) {
                        if (!reachableNames.contains(namedChild.getName())) {
                            toVisit.add(namedChild);
                        }
                    }
                }
            }
        }

        return reachableTypes;
    }

    private boolean containerIsEmpty(GraphQLInputObjectType node) {
        return node.getFields().stream()
                .noneMatch(this::assertFeatureFlagsMatch);
    }

    private boolean containerIsEmpty(GraphQLFieldsContainer obj) {
        return obj.getFields().stream()
                .noneMatch(this::assertFeatureFlagsMatch);
    }

    private boolean containerIsEmpty(GraphQLEnumType enumType) {
        return enumType.getValues().stream().noneMatch(this::assertFeatureFlagsMatch);
    }

    private boolean assertFeatureFlagsMatch(DirectivesContainer<?> container) {
        if (container == null || !container.hasDirective(FEATURE.getName())) {
            return true;
        }
        var args = getOptionalDirectiveArgumentStringList(container, FEATURE, FEATURE.getParamName(FLAGS));
        return featureFlags.containsAll(args);
    }

    private boolean assertFeatureFlagsMatch(GraphQLDirectiveContainer container) {
        if (container == null) {
            return true;
        }

        var directives = container.getAppliedDirectives(FEATURE.getName());
        var args = directives.stream()
                .map(dir -> dir.getArgument(FEATURE.getParamName(FLAGS)))
                .flatMap(dir -> {
                    var value = dir.getValue();
                    if (value instanceof List) {
                        return ((List<String>) value).stream();
                    }

                    return Stream.of((String) value);
                })
                .collect(Collectors.toSet());

        return featureFlags.containsAll(args);
    }
}
