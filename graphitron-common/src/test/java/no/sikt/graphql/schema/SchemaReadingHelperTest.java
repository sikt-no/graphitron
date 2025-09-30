package no.sikt.graphql.schema;

import graphql.language.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;


@DisplayName("SchemaReadingHelper - Reading schemas from filesystem and classpath")
class SchemaReadingHelperTest {

    @TempDir
    Path tempDir;

    private Path testSchemaFile;

    @BeforeEach
    void setUp() throws IOException {
        testSchemaFile = tempDir.resolve("test-schema.graphqls");
        Files.writeString(testSchemaFile, """
                type Query {
                    hello: String
                }
                
                type User {
                    id: ID!
                    name: String
                }
                """);
    }

    @Test
    @DisplayName("Should read schema from filesystem using fileAsString")
    void shouldReadSchemaFromFilesystem() {
        String content = SchemaReadingHelper.fileAsString(testSchemaFile);

        assertThat(content).contains("type User");
    }

    @Test
    @DisplayName("Should read schemas from filesystem paths")
    void shouldReadSchemasFromFilesystem() {
        Document document = SchemaReadingHelper.readSchemas(Set.of(testSchemaFile.toString()));

        assertThat(document).isNotNull();
        assertThat(document.getDefinitions()).isNotEmpty();
    }

    @Test
    @DisplayName("Should read schema from classpath")
    void shouldReadSchemaFromClasspath() {
        Document document = SchemaReadingHelper.readSchemas(Set.of("test-schema-classpath.graphqls"));

        assertThat(document).isNotNull();
        assertThat(document.getDefinitions()).isNotEmpty();
    }

    @Test
    @DisplayName("Should throw exception when schema not found")
    void shouldThrowExceptionWhenSchemaNotFound() {
        assertThatThrownBy(() ->
                SchemaReadingHelper.readSchemas(Set.of("non-existent-schema.graphqls"))
        )
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Schema file not found: non-existent-schema.graphqls");
    }
}