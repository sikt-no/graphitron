package no.fellesstudentsystem.schema_transformer.transform;

import com.apollographql.federation.graphqljava.FederationDirectives;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static no.fellesstudentsystem.schema_transformer.schema.SchemaWriter.writeSchemaToString;
import static org.assertj.core.api.Assertions.assertThat;

public class FederationTest extends AbstractTest {

    @Test
    @DisplayName("Federation definitions and types should be added when federation is imported via schema link")
    void addFederation() {
        var transformedSchema = makeSchema("federationWithLink");
        assertThat(transformedSchema.getDirective(FederationDirectives.key.getName())).isNotNull();

        var query = transformedSchema.getObjectType("Query");

        // Testing only these two here, but there are several other fields and types that are also added.
        assertThat(query.getFieldDefinition("_entities")).isNotNull();
        assertThat(transformedSchema.getType("_Entity")).isNotNull();
    }

    @Test
    @DisplayName("removeFederationDefinitions should remove federation definitions and types from schema")
    void removeFederationDefinitions() {
        var transformedSchema = makeSchema("federationWithLink");
        String schemaWithoutFederationString = writeSchemaToString(transformedSchema, true);

        assertThat(schemaWithoutFederationString).contains("type A");

        assertThat(schemaWithoutFederationString).contains("@key"); //applied directives should not be removed
        assertThat(schemaWithoutFederationString).contains("directive @key"); // directive definitions should not be removed

        assertThat(schemaWithoutFederationString).doesNotContain("_entities");
        assertThat(schemaWithoutFederationString).doesNotContain("_Entity");
        assertThat(schemaWithoutFederationString).doesNotContain("_entities");
        assertThat(schemaWithoutFederationString).doesNotContain("_Service");
        assertThat(schemaWithoutFederationString).doesNotContain("_service");
        assertThat(schemaWithoutFederationString).doesNotContain("_Any");
    }

    @Test
    @DisplayName("Federation definitions and types should not be added when federation is not imported via schema link")
    void donNotAddFederation() {
        var transformedSchema = makeSchema("federationWithoutLink");

        var query = transformedSchema.getObjectType("Query");
        assertThat(query.getFieldDefinition("_entities")).isNull();
        assertThat(transformedSchema.getType("_Entity")).isNull();
    }
}
