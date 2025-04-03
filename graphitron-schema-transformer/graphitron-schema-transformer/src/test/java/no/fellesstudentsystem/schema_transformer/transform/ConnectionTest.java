package no.fellesstudentsystem.schema_transformer.transform;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static no.fellesstudentsystem.schema_transformer.SchemaTransformer.assembleSchema;
import static no.fellesstudentsystem.schema_transformer.schema.SchemaReader.getTypeDefinitionRegistry;

public class ConnectionTest extends AbstractTest {
    @Test
    @DisplayName("Connections are made for types with the appropriate directive")
    void createsNestedPagination() {
        var path = "createNestedPagination";
        var registry = getTypeDefinitionRegistry(findSchemas(path));
        MakeConnections.transform(registry);
        var schema = assembleSchema(registry);
        assertTransformedSchemaMatches(path, schema);
        assertTransformedDirectivesMatch(path, schema);
    }

    @Test
    @DisplayName("Connections already exist and therefore should not be added")
    void connectionExists() {
        var path = "connectionExists";
        var registry = getTypeDefinitionRegistry(findSchemas(path));
        MakeConnections.transform(registry);
        var schema = assembleSchema(registry);
        assertTransformedSchemaMatches(path, schema);
    }
}
