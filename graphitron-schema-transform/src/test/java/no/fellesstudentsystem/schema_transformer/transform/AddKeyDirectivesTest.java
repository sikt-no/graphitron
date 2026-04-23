package no.fellesstudentsystem.schema_transformer.transform;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static no.fellesstudentsystem.schema_transformer.SchemaTransformer.assembleSchema;
import static no.fellesstudentsystem.schema_transformer.schema.SchemaReader.getTypeDefinitionRegistry;

public class AddKeyDirectivesTest extends AbstractTest {

    private static final String BASE = "addKeyDirectives/";

    @Test
    @DisplayName("No-op when no type implements Node")
    void withoutNode() {
        var path = BASE + "withoutNode";
        var registry = getTypeDefinitionRegistry(findSchemas(path + "/schema"));
        AddKeyDirectives.transform(registry);
        var schema = assembleSchema(registry);
        var expected = makeExpectedSchema(path);
        assertTransformedSchemaMatches(schema, expected);
        assertDirectives(schema, expected);
    }

    @Test
    @DisplayName("Adds @key(fields: \"id\") to types implementing Node")
    void addsKeyToNode() {
        var path = BASE + "addsKeyToNode";
        var registry = getTypeDefinitionRegistry(findSchemas(path + "/schema"));
        AddKeyDirectives.transform(registry);
        var schema = assembleSchema(registry);
        var expected = makeExpectedSchema(path);
        assertTransformedSchemaMatches(schema, expected);
        assertDirectives(schema, expected);
    }

    @Test
    @DisplayName("Does not duplicate @key(fields: \"id\") when already present")
    void skipsExistingKey() {
        var path = BASE + "skipsExistingKey";
        var registry = getTypeDefinitionRegistry(findSchemas(path + "/schema"));
        AddKeyDirectives.transform(registry);
        var schema = assembleSchema(registry);
        var expected = makeExpectedSchema(path);
        assertTransformedSchemaMatches(schema, expected);
        assertDirectives(schema, expected);
    }

    @Test
    @DisplayName("Adds @key to every type implementing Node and leaves non-Node types alone")
    void multipleImplementors() {
        var path = BASE + "multipleImplementors";
        var registry = getTypeDefinitionRegistry(findSchemas(path + "/schema"));
        AddKeyDirectives.transform(registry);
        var schema = assembleSchema(registry);
        var expected = makeExpectedSchema(path);
        assertTransformedSchemaMatches(schema, expected);
        assertDirectives(schema, expected);
    }

    @Test
    @DisplayName("Adds @key(fields: \"id\") alongside an existing @key with different fields and preserves other directives")
    void preservesOtherKey() {
        var path = BASE + "preservesOtherKey";
        var registry = getTypeDefinitionRegistry(findSchemas(path + "/schema"));
        AddKeyDirectives.transform(registry);
        var schema = assembleSchema(registry);
        var expected = makeExpectedSchema(path);
        assertTransformedSchemaMatches(schema, expected);
        assertDirectives(schema, expected);
    }
}
