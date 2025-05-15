package no.fellesstudentsystem.schema_transformer.transform;

import graphql.schema.*;
import graphql.util.TraversalControl;
import graphql.util.TraverserContext;
import no.fellesstudentsystem.schema_transformer.schema.SchemaReader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static graphql.util.TraversalControl.CONTINUE;
import static no.fellesstudentsystem.schema_transformer.SchemaTransformer.assembleSchema;
import static no.fellesstudentsystem.schema_transformer.schema.SchemaHelpers.isInternal;
import static no.fellesstudentsystem.schema_transformer.schema.SchemaReader.getTypeDefinitionRegistry;
import static org.assertj.core.api.Assertions.assertThat;

public class FeatureAdditionsTest extends AbstractTest {
    @Test
    @DisplayName("Comments are added as specified per feature")
    void addComments() {
        var path = "addComments";
        var schema = makeTestSchema(path);
        var mapping = SchemaReader.createDescriptionSuffixForFeatureMap(Set.of(SRC_TEST_RESOURCES + "/" + path), "description-suffix.md");
        var newSchema = new FeatureConfiguration(schema, mapping, false).getModifiedGraphQLSchema();

        var expected = makeExpectedSchema(path);
        assertTransformedSchemaMatches(expected, newSchema);
        assertDescriptions(expected, newSchema);
    }

    @Test
    @DisplayName("Feature directives are applied based on the directory structure")
    void addFeatures() {
        var path = "addFeatures";
        var schema = makeTestSchema(path);
        var newSchema = new FeatureConfiguration(schema, Map.of(), false).getModifiedGraphQLSchema();

        var expected = makeExpectedSchema(path);
        assertTransformedSchemaMatches(newSchema, expected);
        assertDirectives(newSchema, expected);
    }

    @Test
    @DisplayName("Tag directives are applied based on the directory structure")
    void addTags() {
        var path = "addTags";
        var schema = makeTestSchema(path);
        var newSchema = new FeatureConfiguration(schema, Map.of(), true).getModifiedGraphQLSchema();

        var expected = makeExpectedSchema(path);
        assertTransformedSchemaMatches(newSchema, expected);
        assertDirectives(newSchema, expected);
    }

    @Test
    @DisplayName("Tag directives are applied on relevant generated connection types")
    void addTagsOnConnectionTypes() {
        var path = "addTagsOnConnectionTypes";
        var registry = getTypeDefinitionRegistry(findSchemas(Set.of(path + "/schema")));
        MergeExtensions.transform(registry);
        MakeConnections.transform(registry);  // Add connections and such first.
        var connectionSchema = assembleSchema(registry);
        var newSchema = new FeatureConfiguration(connectionSchema, Map.of(), true).getModifiedGraphQLSchema();

        var expected = makeExpectedSchema(path);
        assertDirectives(newSchema, expected);
    }

    @Test
    @DisplayName("Tag directives are applied on unions based on the directory structure")
    void addTagsUnion() {
        var path = "addTagsUnion";
        var schema = makeTestSchema(path);
        var newSchema = new FeatureConfiguration(schema, Map.of(), true).getModifiedGraphQLSchema();

        var expected = makeExpectedSchema(path);
        assertTransformedSchemaMatches(newSchema, expected);
        assertDirectives(newSchema, expected);
    }

    private static void assertDescriptions(GraphQLSchema generatedSchema, GraphQLSchema expectedSchema) {
        var actualPatterns = getDescriptionPatterns(generatedSchema);
        var expectedPatterns = getDescriptionPatterns(expectedSchema);
        assertThat(actualPatterns).containsExactlyInAnyOrderElementsOf(expectedPatterns);
    }

    private static List<String> getDescriptionPatterns(GraphQLSchema generatedSchema) {
        var patterns = new LinkedHashSet<String>();
        new SchemaTraverser().depthFirstFullSchema(new GraphQLTypeVisitorStub() {
            @Override
            protected TraversalControl visitGraphQLType(GraphQLSchemaElement node, TraverserContext<GraphQLSchemaElement> context) {
                if (!isInternal(context) && node instanceof GraphQLNamedSchemaElement namedNode && !(node instanceof GraphQLDirective)) {
                    patterns.add(getDescriptionPattern(namedNode, context));
                }
                return CONTINUE;
            }
        }, generatedSchema);
        return patterns.stream().sorted().toList();
    }

    private static String getDescriptionPattern(GraphQLNamedSchemaElement node, TraverserContext<GraphQLSchemaElement> context) {
        var path = getNodePath(context);
        var description = node.getDescription();
        return "\u001B[0;35m" + path + "\u001B[0m" + (description == null || description.isEmpty() ? " with no description" : " with description\u001B[0;35m \"" + description.replaceAll("\\s+", " ") + "\"\u001B[0m");
    }
}
