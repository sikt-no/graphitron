package no.fellesstudentsystem.schema_transformer.transform;

import no.fellesstudentsystem.schema_transformer.schema.SchemaReader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

public class FeatureAdditionsTest extends AbstractTest {
    @Test
    @DisplayName("Comments are added as specified per feature")
    void addComments() {
        var path = "addComments";
        var schema = makeSchema(findSchemas(Set.of(path + "/schema")));
        var mapping = SchemaReader.createDescriptionSuffixForFeatureMap(Set.of(SRC_TEST_RESOURCES + "/" + path), "description-suffix.md");
        var newSchema = new FeatureFlagConfiguration(schema, mapping).getModifiedGraphQLSchema();
        assertTransformedSchemaMatches(path, newSchema);
        assertTransformedDirectivesMatch(path, newSchema);
    }

    @Test
    @DisplayName("Feature directives are applied based on the directory structure")
    void addFeatures() {
        var path = "addFeatures";
        var schema = makeSchema(findSchemas(Set.of(path + "/schema")));
        var newSchema = new FeatureFlagConfiguration(schema, Map.of()).getModifiedGraphQLSchema();
        assertTransformedSchemaMatches(path, newSchema);
        assertTransformedDirectivesMatch(path, newSchema);
    }
}
