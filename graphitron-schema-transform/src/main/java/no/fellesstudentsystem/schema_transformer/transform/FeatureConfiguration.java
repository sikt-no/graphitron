package no.fellesstudentsystem.schema_transformer.transform;

import graphql.language.*;
import graphql.schema.*;
import graphql.util.TraversalControl;
import graphql.util.TraverserContext;

import java.io.File;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static graphql.util.TraversalControl.CONTINUE;
import static no.fellesstudentsystem.schema_transformer.mapping.GraphQLDirective.FEATURE;
import static no.fellesstudentsystem.schema_transformer.mapping.GraphQLDirectiveParam.FLAGS;
import static no.fellesstudentsystem.schema_transformer.schema.SchemaHelpers.getOptionalDirectiveArgumentStringList;
import static no.fellesstudentsystem.schema_transformer.schema.SchemaHelpers.isInternal;
import static no.sikt.graphql.naming.GraphQLReservedName.*;

/**
 * Class for adding feature flags based on schema directory paths.
 */
public class FeatureConfiguration implements ModifyingGraphQLTypeVisitor {
    public static final String FEATURES_DIRECTORY = File.separator + "features" + File.separator;
    private final GraphQLSchema schema;
    private final boolean hasTags;
    private final GraphQLAppliedDirective featureDirective, tagDirective;
    private final GraphQLAppliedDirectiveArgument featureArgument, tagArgument;
    private final Map<String, String> descriptionSuffixForFeature;

    public FeatureConfiguration(GraphQLSchema schema, Map<String, String> descriptionSuffixForFeature, boolean addTags) {
        this.schema = schema;
        this.descriptionSuffixForFeature = descriptionSuffixForFeature;
        hasTags = addTags;
        var feature = schema.getDirective(FEATURE.getName());  // Note: getDirectivesByName only shows non-repeatable directives.
        if (feature != null) {
            featureDirective = feature.toAppliedDirective();
            featureArgument = featureDirective.getArgument(FEATURE.getParamName(FLAGS));
        } else {
            featureDirective = null;
            featureArgument = null;
        }

        var tag = schema.getDirective(FEDERATION_TAG.getName());
        // This check for tag import was added for Hive federation, but this is not a very flexible solution and may be subject to change.
        // Checking that the directive exists should be enough.
        if (hasTags && tag != null && federationImported(schema, "@" + FEDERATION_TAG.getName())) {
            tagDirective = tag.toAppliedDirective();
            tagArgument = tagDirective.getArgument(FEDERATION_TAG_ARGUMENT.getName());
        } else {
            tagDirective = null;
            tagArgument = null;
        }
    }

    private static boolean federationImported(GraphQLSchema schema, String value) {
        return Optional
                .ofNullable(schema.getSchemaAppliedDirective(FEDERATION_LINK.getName()))
                .flatMap(it -> Optional.ofNullable(it.getArgument(FEDERATION_LINK_IMPORT.getName())))
                .map(it -> ((ArrayList<?>) it.getValue()).stream().anyMatch(val -> val.equals(value)))
                .orElse(false);
    }

    @Override
    public GraphQLSchema getModifiedGraphQLSchema() {
        var visitor = new GraphQLTypeVisitorStub() {
            @Override
            public TraversalControl visitGraphQLFieldDefinition(GraphQLFieldDefinition node, TraverserContext<GraphQLSchemaElement> context) { // Includes interface fields.
                if (isInternal(context)) {
                    return CONTINUE;
                }

                var newFeatureDirective = getNewFeatureDirectiveFor(node);
                var newTagDirective = getTagDirectiveFor(node);
                var directives = Stream.of(newFeatureDirective, newTagDirective).filter(Objects::nonNull).toList();
                if (node.getDefinition() == null || directives.isEmpty()) {
                    return CONTINUE;
                }

                return changeNode(context, node.transform(builder ->
                        builder
                                .description(getDescriptionForNode(node, newFeatureDirective))
                                .replaceAppliedDirectives(concatDirectives(node.getDirectives(), directives))
                        )
                );
            }

            @Override
            public TraversalControl visitGraphQLInputObjectField(GraphQLInputObjectField node, TraverserContext<GraphQLSchemaElement> context) {
                if (isInternal(context)) {
                    return CONTINUE;
                }

                var newFeatureDirective = getNewFeatureDirectiveFor(node);
                var newTagDirective = getTagDirectiveFor(node);
                var directives = Stream.of(newFeatureDirective, newTagDirective).filter(Objects::nonNull).toList();
                if (directives.isEmpty()) {
                    return CONTINUE;
                }

                return changeNode(context, node.transform(builder ->
                        builder
                                .description(getDescriptionForNode(node, newFeatureDirective))
                                .replaceAppliedDirectives(concatDirectives(node.getDirectives(), directives))
                        )
                );
            }

            @Override
            public TraversalControl visitGraphQLEnumValueDefinition(GraphQLEnumValueDefinition node, TraverserContext<GraphQLSchemaElement> context) {
                if (isInternal(context)) {
                    return CONTINUE;
                }

                var newFeatureDirective = getNewFeatureDirectiveFor(node);
                var newTagDirective = getTagDirectiveFor(node);
                var directives = Stream.of(newFeatureDirective, newTagDirective).filter(Objects::nonNull).toList();
                if (directives.isEmpty()) {
                    return CONTINUE;
                }

                return changeNode(context, node.transform(builder ->
                        builder
                                .description(getDescriptionForNode(node, newFeatureDirective))
                                .replaceAppliedDirectives(concatDirectives(node.getDirectives(), directives))
                        )
                );
            }

            @Override
            public TraversalControl visitGraphQLArgument(GraphQLArgument node, TraverserContext<GraphQLSchemaElement> context) {
                if (context.getParentNode() instanceof GraphQLDirective || isInternal(context)) {
                    return CONTINUE;
                }

                var newFeatureDirective = getNewFeatureDirectiveFor(node);
                var newTagDirective = getTagDirectiveFor(node);
                var directives = Stream.of(newFeatureDirective, newTagDirective).filter(Objects::nonNull).toList();
                if (directives.isEmpty()) {
                    return CONTINUE;
                }

                return changeNode(context, node.transform(builder ->
                        builder
                                .description(getDescriptionForNode(node, newFeatureDirective))
                                .replaceAppliedDirectives(concatDirectives(node.getDirectives(), directives))
                        )
                );
            }

            @Override
            public TraversalControl visitGraphQLUnionType(GraphQLUnionType node, TraverserContext<GraphQLSchemaElement> context) {
                if (isInternal(context)) {
                    return CONTINUE;
                }

                var newTagDirective = getTagDirectiveFor(node);
                if (newTagDirective == null) {
                    return CONTINUE;
                }

                return changeNode(context, node.transform(builder ->
                                builder.replaceAppliedDirectives(concatDirectives(node.getDirectives(), List.of(newTagDirective)))
                        )
                );
            }
        };

        return SchemaTransformer.transformSchema(schema, visitor);
    }

    private GraphQLAppliedDirective getNewFeatureDirectiveFor(GraphQLNamedSchemaElement node) {
        if (featureDirective == null || node.getDefinition() == null) {
            return null;
        }
        var definition = node.getDefinition();
        var featuresFromDirectories = getOptionalDirectoryPath(definition.getSourceLocation());
        if (featuresFromDirectories.isEmpty()) {
            return null;
        }

        var existingFeatures = getOptionalDirectiveArgumentStringList((DirectivesContainer<?>) definition, FEATURE, FEATURE.getParamName(FLAGS));
        if (!existingFeatures.isEmpty()) {
            return null;
        }

        var newDirectiveValue = new ArrayValue(
                featuresFromDirectories.stream()
                        .map(StringValue::new)
                        .collect(Collectors.toList()));

        return transformDirective(featureDirective, featureArgument, newDirectiveValue, getDescriptionForNode(node, newDirectiveValue));
    }

    private GraphQLAppliedDirective getTagDirectiveFor(GraphQLNamedSchemaElement node) {
        if (!hasTags || tagDirective == null || node.getDefinition() == null) {
            return null;
        }
        var definition = node.getDefinition();
        var directoryPath = getOptionalDirectoryPath(definition.getSourceLocation());
        if (directoryPath.isEmpty()) {
            return null;
        }

        var existingTags = ((DirectivesContainer<?>) definition).getDirectives(tagDirective.getName());
        if (!existingTags.isEmpty()) {
            return null;
        }

        return transformDirective(tagDirective, tagArgument, new StringValue(directoryPath.get(0)), null);
    }

    private GraphQLAppliedDirective transformDirective(GraphQLAppliedDirective directive, GraphQLAppliedDirectiveArgument argument, Value<?> value, String description) {
        var newArguments = argument.transform(builder -> builder.inputValueWithState(InputValueWithState.newLiteralValue(value)));
        return directive.transform(builder -> {
            builder = builder.replaceArguments(List.of(newArguments));
            if (description != null && !description.isEmpty()) {
                builder.description(description).build();
            }
        });
    }

    private List<String> getOptionalDirectoryPath(SourceLocation location) {
        if (location == null || location.getSourceName() == null) { // When we add things in previous steps, such as connection types.
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

    private List<GraphQLAppliedDirective> concatDirectives(List<GraphQLDirective> previousDirectives, List<GraphQLAppliedDirective> newDirectives) {
        return Stream.concat(
                previousDirectives
                        .stream()
                        .filter(it -> !it.getName().equals(FEATURE.getName()))
                        .map(GraphQLDirective::toAppliedDirective),
                newDirectives.stream()
        ).toList();
    }

    private String getDescriptionForNode(GraphQLNamedSchemaElement node, GraphQLAppliedDirective newDirective) {
        if (newDirective == null) {
            return node.getDescription();
        }
        return getDescriptionWithSuffixBasedOnFeatureFlags(node, newDirective.getArgument(FEATURE.getParamName(FLAGS)).getValue());
    }

    private String getDescriptionForNode(GraphQLNamedSchemaElement node, ArrayValue value) {
        List<String> featureFlags = value != null ? value.getValues().stream().map(it -> ((StringValue) it).getValue()).toList() : List.of();
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
