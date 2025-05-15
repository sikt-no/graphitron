package no.fellesstudentsystem.schema_transformer.transform;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static no.fellesstudentsystem.schema_transformer.SchemaTransformer.assembleSchema;
import static no.fellesstudentsystem.schema_transformer.schema.SchemaReader.getTypeDefinitionRegistry;

public class ConnectionTest extends AbstractTest {
    @Test
    @DisplayName("Connections are made for types with the appropriate directive")
    void createsNestedPagination() {
        var path = "createNestedPagination";
        var registry = getTypeDefinitionRegistry(findSchemas(path + "/schema"));
        MakeConnections.transform(registry);
        var schema = assembleSchema(registry);
        var expected = makeExpectedSchema(path);
        assertTransformedSchemaMatches(schema, expected);
        assertDirectives(schema, expected);
    }

    @Test
    @DisplayName("Connections already exist and therefore should not be added")
    void connectionExists() {
        var path = "connectionExists";
        var registry = getTypeDefinitionRegistry(findSchemas(path + "/schema"));
        MakeConnections.transform(registry);
        var schema = assembleSchema(registry);
        var expected = makeExpectedSchema(path);
        assertTransformedSchemaMatches(schema, expected);
    }
}
