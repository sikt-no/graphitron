package no.fellesstudentsystem.schema_transformer.transform;

import no.fellesstudentsystem.schema_transformer.SchemaTransformer;
import no.fellesstudentsystem.schema_transformer.TransformConfig;
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

    @Test
    @DisplayName("Includes all optional connection fields by default")
    void shouldIncludeAllOptionalFieldsByDefault() {
        var path = "optionalConnectionFields/allDefault";
        var transformConfig = createAndInitializeTransformConfig(
                path + "/schema",
                true,
                true
        );
        var schemaTransformer = new SchemaTransformer(transformConfig);
        var transformedSchema = schemaTransformer.transformSchema();
        var expectedSchema = makeExpectedSchema(path);
        assertTransformedSchemaExactlyMatches(transformedSchema, expectedSchema);
    }

    @Test
    @DisplayName("Excludes all optional connection fields when explicitly disabled")
    void shouldExcludeAllOptionalFieldsWhenExplicitlyDisabled() {
        var path = "optionalConnectionFields/allDisabled";
        var transformConfig = createAndInitializeTransformConfig(
                path + "/schema",
                false,
                false
        );
        var schemaTransformer = new SchemaTransformer(transformConfig);
        var transformedSchema = schemaTransformer.transformSchema();
        var expectedSchema = makeExpectedSchema(path);
        assertTransformedSchemaExactlyMatches(transformedSchema, expectedSchema);
    }

    @Test
    @DisplayName("Includes optional connection fields by default and correctly excludes those explicitly disabled")
    void shouldHandleDefaultAndExplicitlyDisabledOptionalFields() {
        var path = "optionalConnectionFields/defaultAndDisabled";
        var transformConfig = createAndInitializeTransformConfig(
                path + "/schema",
                false,
                true
        );
        var schemaTransformer = new SchemaTransformer(transformConfig);
        var transformedSchema = schemaTransformer.transformSchema();
        var expectedSchema = makeExpectedSchema(path);
        assertTransformedSchemaExactlyMatches(transformedSchema, expectedSchema);
    }

    private TransformConfig createAndInitializeTransformConfig(
            String testPath,
            boolean nodesFieldInConnectionsEnabled,
            boolean totalCountFieldInConnectionsEnabled
    ) {
        var schemaPath = findSchemas(testPath);

        return new TransformConfig(
                schemaPath,
                Set.of(),
                null,
                false,
                true,
                true,
                true,
                nodesFieldInConnectionsEnabled,
                totalCountFieldInConnectionsEnabled
        );
    }
}
