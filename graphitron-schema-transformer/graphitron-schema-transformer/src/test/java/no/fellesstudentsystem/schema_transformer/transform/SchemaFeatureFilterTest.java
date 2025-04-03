package no.fellesstudentsystem.schema_transformer.transform;

import graphql.schema.GraphQLEnumType;
import graphql.schema.GraphQLInputObjectType;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLSchema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.fellesstudentsystem.schema_transformer.SchemaTransformer.assembleSchema;
import static no.fellesstudentsystem.schema_transformer.schema.SchemaReader.getTypeDefinitionRegistry;
import static no.fellesstudentsystem.schema_transformer.schema.SchemaWriter.writeSchemaToString;
import static org.assertj.core.api.Assertions.assertThat;

class SchemaFeatureFilterTest {
    private final static String SRC_TEST_RESOURCES = "src/test/resources/transform/";

    @Test
    void filter_shouldIncludeOnlyWithoutFeatures() {
        var schema = createSchema("noFlagsRequested/test.graphql");
        var expected = createSchema("noFlagsRequested/expected.graphql");
        var filteredSchema = new SchemaFeatureFilter().getFilteredGraphQLSchema(schema);
        assertThat(writeSchemaToString(filteredSchema)).isEqualToIgnoringWhitespace(writeSchemaToString(expected));
    }

    @Test
    void filter_shouldRemoveNestedTypes() {
        var schema = createSchema("removesNestedTypes/test.graphql");
        var expected = createSchema("removesNestedTypes/expected.graphql");
        testSchema(new SchemaFeatureFilter().getFilteredGraphQLSchema(schema), expected);
    }

    @Test
    void filter_shouldRemoveNestedInputs() {
        var schema = createSchema("removesNestedInputs/test.graphql");
        var expected = createSchema("removesNestedInputs/expected.graphql");
        testSchema(new SchemaFeatureFilter().getFilteredGraphQLSchema(schema), expected);
    }

    @Test
    void filter_shouldRemoveNestedInterfaces() {
        var schema = createSchema("removesNestedInterfaces/test.graphql");
        var expected = createSchema("removesNestedInterfaces/expected.graphql");
        testSchema(new SchemaFeatureFilter().getFilteredGraphQLSchema(schema), expected);
    }

    @Test
    void filter_shouldRemoveInputsIfFieldArgumentsAreRemoved() {
        var schema = createSchema("removesInputWhenFieldWithArgumentRemoved/test.graphql");
        var expected = createSchema("removesInputWhenFieldWithArgumentRemoved/expected.graphql");
        testSchema(new SchemaFeatureFilter().getFilteredGraphQLSchema(schema), expected);
    }

    @Test
    void filter_shouldRemoveInputsIfArgumentsAreRemoved() {
        var schema = createSchema("removesInputWhenArgumentRemoved/test.graphql");
        var expected = createSchema("removesInputWhenArgumentRemoved/expected.graphql");
        testSchema(new SchemaFeatureFilter().getFilteredGraphQLSchema(schema), expected);
    }

    @Test
    void filter_shouldSelectOnlyRequestedFeatures() {
        var schema = createSchema("selectsRequestedFeatures/test.graphql");
        var expected = createSchema("selectsRequestedFeatures/expected.graphql");
        testSchema(new SchemaFeatureFilter(Set.of("F0", "F1")).getFilteredGraphQLSchema(schema), expected);
    }

    @Test
    void filter_shouldSelectOnlyRequestedListFeatures() {
        var schema = createSchema("selectsRequestedListFeatures/test.graphql");
        var expected = createSchema("selectsRequestedListFeatures/expected.graphql");
        testSchema(new SchemaFeatureFilter(Set.of("F0", "F1")).getFilteredGraphQLSchema(schema), expected);
    }

    @Test
    void filter_shouldRemoveUnusedTypes() {
        var schema = createSchema("removesUnusedTypes/test.graphql");
        var expected = createSchema("removesUnusedTypes/expected.graphql");
        testSchema(new SchemaFeatureFilter(Set.of()).getFilteredGraphQLSchema(schema), expected);
    }

    @Test
    void filter_shouldRemoveEmptyTypes() {
        var schema = createSchema("removesEmptyTypes/test.graphql");
        var filteredSchema = new SchemaFeatureFilter().getFilteredGraphQLSchema(schema); // No schema exception.
        var allTypes = filteredSchema.getAllTypesAsList();
        allTypes.forEach(it -> {
            if (it instanceof GraphQLObjectType) {
                assertThat(((GraphQLObjectType)it).getFields()).isNotEmpty();
            } else if (it instanceof GraphQLInputObjectType) {
                assertThat(((GraphQLInputObjectType)it).getFields()).isNotEmpty();
            } else if (it instanceof GraphQLEnumType) {
                assertThat(((GraphQLEnumType)it).getValues()).isNotEmpty();
            }
        });

        assertThat(allTypes)
                .filteredOn(it -> it instanceof GraphQLObjectType && !it.getName().startsWith("__"))
                .hasSize(2);
    }

    private GraphQLSchema createSchema(String file) {
        return assembleSchema(getTypeDefinitionRegistry(List.of(SRC_TEST_RESOURCES + file, SRC_TEST_RESOURCES + "defaultDirectives.graphql")));
    }

    private void testSchema(GraphQLSchema actual, GraphQLSchema expected) {
        assertThat(writeSchemaToString(actual)).isEqualToIgnoringWhitespace(writeSchemaToString(expected));
    }
}
