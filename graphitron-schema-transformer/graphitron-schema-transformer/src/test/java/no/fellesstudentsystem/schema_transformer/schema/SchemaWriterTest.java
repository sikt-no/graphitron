package no.fellesstudentsystem.schema_transformer.schema;

import graphql.schema.GraphQLSchema;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaWriterTest {

    private GraphQLSchema schema;

    private static final List<String> FEDERATION_DEFINITIONS = List.of(
            "directive @key",
            "directive @requires",
            "directive @provides",
            "scalar Import",
            "scalar FieldSet",
            "enum Purpose");

    private static final List<String> APPLIED_DIRECTIVES = List.of(
            "type SomeType @key(fields",
            "name: String @external");

    @BeforeEach
    void setUp() throws IOException {
        String schemaString = Files.readString(Paths.get("src/test/resources/federationDirectives/schema.graphql"));
        TypeDefinitionRegistry typeDefinitionRegistry = new SchemaParser().parse(schemaString);
        schema = SchemaWriter.assembleSchema(typeDefinitionRegistry);
    }

    @Test
    @DisplayName("Should keep federation definitions in the schema string")
    void keepFederationDefinitions() {
        String schemaWithFederationDefs = SchemaWriter.writeSchemaToString(schema, true, true);

        assertThat(schemaWithFederationDefs).contains(APPLIED_DIRECTIVES);
        assertThat(schemaWithFederationDefs).contains(FEDERATION_DEFINITIONS);
    }

    @Test
    @DisplayName("Should exclude federation definitions from the schema string")
    void excludeFederationDefinitions() {
        String schemaWithoutFederationDefs = SchemaWriter.writeSchemaToString(schema, true, false);

        assertThat(schemaWithoutFederationDefs).contains(APPLIED_DIRECTIVES); //should contain the applied federation directives
        assertThat(schemaWithoutFederationDefs).doesNotContain(FEDERATION_DEFINITIONS);
    }
}