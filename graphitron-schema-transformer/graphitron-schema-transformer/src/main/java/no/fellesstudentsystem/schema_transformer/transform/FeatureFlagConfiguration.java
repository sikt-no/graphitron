package no.fellesstudentsystem.schema_transformer.transform;

import graphql.language.ArrayValue;
import graphql.language.DirectivesContainer;
import graphql.language.SourceLocation;
import graphql.language.StringValue;
import graphql.schema.*;
import graphql.util.TraversalControl;
import graphql.util.TraverserContext;
import no.fellesstudentsystem.schema_transformer.mapping.FieldType;
import no.sikt.graphql.naming.GraphQLReservedName;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static graphql.util.TraversalControl.CONTINUE;
import static no.fellesstudentsystem.schema_transformer.mapping.GraphQLDirective.FEATURE;
import static no.fellesstudentsystem.schema_transformer.mapping.GraphQLDirectiveParam.FLAGS;
import static no.fellesstudentsystem.schema_transformer.schema.SchemaHelpers.getOptionalDirectiveArgumentStringList;

import static no.sikt.graphql.naming.GraphQLReservedName.CONNECTION_EDGE_FIELD;

/**
 * Class for adding feature flags based on schema directory paths.
 */
public class FeatureFlagConfiguration implements ModifyingGraphQLTypeVisitor {
    public static final String FEATURES_DIRECTORY = File.separator + "features" + File.separator;
    private final GraphQLSchema schema;
    private final GraphQLAppliedDirective featureDirective;
    private final GraphQLAppliedDirectiveArgument featureArgument;
    private final Map<String, GraphQLObjectType> typeMap;
    private final HashMap<String, GraphQLAppliedDirective> connectionFeaturesMap = new HashMap<>();
    private final Map<String, GraphQLAppliedDirective> edgeFeaturesMap = new HashMap<>();
    private final Map<String, String> descriptionSuffixForFeature;

    public FeatureFlagConfiguration(GraphQLSchema schema, Map<String, String> descriptionSuffixForFeature) {
        this.schema = schema;
        this.descriptionSuffixForFeature = descriptionSuffixForFeature;
        var directives = schema.getDirectivesByName();
        if (directives.containsKey(FEATURE.getName())) {
            featureDirective = directives.get(FEATURE.getName()).toAppliedDirective();
            featureArgument = featureDirective.getArgument(FEATURE.getParamName(FLAGS));
        } else {
            featureDirective = null;
            featureArgument = null;
        }

        typeMap = schema
                .getAllTypesAsList()
                .stream()
                .filter(it -> it instanceof GraphQLObjectType)
                .map(it -> (GraphQLObjectType)it)
                .collect(Collectors.toMap(GraphQLNamedSchemaElement::getName, Function.identity()));
    }


    @Override
    public GraphQLSchema getModifiedGraphQLSchema() {
        var updatedSchema = addFeaturesToFields();

        updateEdgeMap();

        var finalSchema = addFeaturesForConnections(updatedSchema);

        connectionFeaturesMap.clear();
        edgeFeaturesMap.clear();
        return finalSchema;
    }

    private GraphQLSchema addFeaturesToFields() {
        var visitor = new GraphQLTypeVisitorStub() {
            @Override
            public TraversalControl visitGraphQLFieldDefinition(GraphQLFieldDefinition node, TraverserContext<GraphQLSchemaElement> context) { // Includes interface fields.
                var newDirective = getNewFeatureDirectiveFor(node);
                if (newDirective == null || node.getDefinition() == null) {
                    return CONTINUE;
                }

                var typeName = new FieldType(node.getDefinition().getType()).getName();
                if (typeName.endsWith(GraphQLReservedName.SCHEMA_CONNECTION_SUFFIX.getName())) {
                    connectionFeaturesMap.put(typeName, newDirective);
                }

                return changeNode(context, node.transform(builder ->
                        builder.description(getDescriptionForNode(node,  newDirective))
                                .replaceAppliedDirectives(collectNewDirectives(node.getDirectives(), newDirective))));
            }

            @Override
            public TraversalControl visitGraphQLInputObjectField(GraphQLInputObjectField node, TraverserContext<GraphQLSchemaElement> context) {
                var newDirective = getNewFeatureDirectiveFor(node);
                if (newDirective == null) {
                    return CONTINUE;
                }

                return changeNode(context, node.transform(builder ->
                        builder.description(getDescriptionForNode(node, newDirective))
                                .replaceAppliedDirectives(collectNewDirectives(node.getDirectives(), newDirective))));
            }

            @Override
            public TraversalControl visitGraphQLEnumValueDefinition(GraphQLEnumValueDefinition node, TraverserContext<GraphQLSchemaElement> context) {
                var newDirective = getNewFeatureDirectiveFor(node);
                if (newDirective == null) {
                    return CONTINUE;
                }

                return changeNode(context, node.transform(builder ->
                        builder.description(getDescriptionForNode(node, newDirective))
                                .replaceAppliedDirectives(collectNewDirectives(node.getDirectives(), newDirective))));
            }

            @Override
            public TraversalControl visitGraphQLArgument(GraphQLArgument node, TraverserContext<GraphQLSchemaElement> context) {
                if (context.getParentNode() instanceof GraphQLDirective) {
                    return CONTINUE;
                }

                var newDirective = getNewFeatureDirectiveFor(node);
                if (newDirective == null) {
                    return CONTINUE;
                }

                return changeNode(context, node.transform(builder ->
                        builder.description(getDescriptionForNode(node, newDirective))
                                .replaceAppliedDirectives(collectNewDirectives(node.getDirectives(), newDirective))));
            }
        };

        return SchemaTransformer.transformSchema(schema, visitor);
    }

    @Nullable
    private GraphQLAppliedDirective getNewFeatureDirectiveFor(GraphQLNamedSchemaElement node) {
        if (featureDirective == null || node.getDefinition() == null) {
            return null;
        }
        var definition = node.getDefinition();
        var featuresFromDirectories = getOptionalFeatureDirectoryPath(definition.getSourceLocation());
        if (featuresFromDirectories.isEmpty()) {
            return null;
        }

        var existingFeatures = getOptionalDirectiveArgumentStringList((DirectivesContainer<?>) definition, FEATURE, FEATURE.getParamName(FLAGS));

        var newDirectiveValue = InputValueWithState.newLiteralValue(new ArrayValue(
                Stream
                        .concat(existingFeatures.stream(), featuresFromDirectories.stream())
                        .distinct()
                        .map(StringValue::new)
                        .collect(Collectors.toList())
        ));

        var newArguments = List.of(featureArgument.transform(builder -> builder.inputValueWithState(newDirectiveValue)));
        return featureDirective.transform(builder ->
                builder.description(getDescriptionForNode(node, newDirectiveValue))
                        .replaceArguments(newArguments).build());
    }

    @NotNull
    private List<String> getOptionalFeatureDirectoryPath(SourceLocation location) {
        if (location == null) { // When we add things in previous steps, such as connection types.
            return List.of();
        }

        var split = List.of(location.getSourceName().split(Pattern.quote(FEATURES_DIRECTORY)));
        if (split.size() < 2) {
            return List.of();
        }

        var pathSplit = List.of(split.get(1).split(Pattern.quote(File.separator)));
        var splitSize = pathSplit.size();
        if (splitSize < 2) {
            return List.of();
        }

        return pathSplit.subList(0, 1); // Changing this to only use the first. Was size-1.
    }

    @NotNull
    private List<GraphQLAppliedDirective> collectNewDirectives(List<GraphQLDirective> previousDirectives, GraphQLAppliedDirective newDir) {
        return Stream.concat(
                previousDirectives
                        .stream()
                        .filter(it -> !it.getName().equals(FEATURE.getName()))
                        .map(GraphQLDirective::toAppliedDirective),
                Stream.of(newDir)
        ).collect(Collectors.toList());
    }

    private GraphQLSchema addFeaturesForConnections(GraphQLSchema updatedSchema) {
        var visitor = new GraphQLTypeVisitorStub() {
            @Override
            public TraversalControl visitGraphQLObjectType(GraphQLObjectType node, TraverserContext<GraphQLSchemaElement> context) {
                var nodeName = node.getName();
                GraphQLAppliedDirective newDirective;
                if (connectionFeaturesMap.containsKey(nodeName)) {
                    newDirective = connectionFeaturesMap.get(nodeName);
                } else if (edgeFeaturesMap.containsKey(nodeName)) {
                    newDirective = edgeFeaturesMap.get(nodeName);
                } else {
                    return CONTINUE;
                }

                var fields = node
                        .getFields()
                        .stream()
                        .map(it -> it.transform(builder ->
                                builder.description(getDescriptionForNode(node, newDirective))
                                        .withAppliedDirective(newDirective)))
                        .collect(Collectors.toList());
                return changeNode(context, node.transform(builder -> builder.replaceFields(fields)));
            }
        };

        return SchemaTransformer.transformSchema(updatedSchema, visitor);
    }

    private void updateEdgeMap() {
        connectionFeaturesMap.forEach((key, value) -> {
            var type = typeMap.get(key);
            var edgeField = type
                    .getFields()
                    .stream()
                    .filter(f -> f.getName().equals(CONNECTION_EDGE_FIELD.getName()))
                    .findFirst();
            if (edgeField.isPresent()) {
                var edgeDefinition = edgeField.get().getDefinition();
                if (edgeDefinition != null) {
                    edgeFeaturesMap.put(
                            typeMap.get(new FieldType(edgeField.get().getDefinition().getType()).getName()).getName(),
                            value
                    );
                }
            }
        });
    }

    private String getDescriptionForNode(GraphQLNamedSchemaElement node, GraphQLAppliedDirective newDirective) {
        List<String> featureFlags = newDirective.getArgument(FEATURE.getParamName(FLAGS)).getValue();
        return getDescriptionWithSuffixBasedOnFeatureFlags(node, featureFlags);
    }

    private String getDescriptionForNode(GraphQLNamedSchemaElement node, InputValueWithState newArguments) {
        List<String> featureFlags = newArguments.getValue() != null && newArguments.getValue() instanceof ArrayValue
                ? ((ArrayValue) newArguments.getValue()).getValues().stream().map(it -> ((StringValue) it).getValue()).collect(Collectors.toList())
                : List.of();
        return getDescriptionWithSuffixBasedOnFeatureFlags(node, featureFlags);
    }

    private String getDescriptionWithSuffixBasedOnFeatureFlags(GraphQLNamedSchemaElement node, List<String> featureFlags) {
        var descriptionSuffix = featureFlags.stream()
                .map(descriptionSuffixForFeature::get)
                .filter(Objects::nonNull)
                .map(String::strip)
                .collect(Collectors.joining(System.lineSeparator()));
        var existingDescription = node.getDescription();
        return existingDescription == null
                ? descriptionSuffix
                : existingDescription.strip() + System.lineSeparator() + System.lineSeparator() + descriptionSuffix;
    }
}
