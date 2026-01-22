package no.sikt.graphitron.maven;

import no.fellesstudentsystem.schema_transformer.OutputSchema;
import no.sikt.graphitron.mojo.GenerateAllExecutor;
import no.sikt.graphitron.mojo.TransformConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("GenerateAllExecutor - Schema transformation orchestration")
class GenerateAllExecutorTest {
    public static final String SCHEMA_GRAPHQLS = "schema.graphqls";

    @TempDir
    Path tempDir;

    private Path schemaDir;
    private Path outputDir;

    @BeforeEach
    void setUp() throws IOException {
        schemaDir = tempDir.resolve("schema");
        outputDir = tempDir.resolve("output");
        Files.createDirectories(schemaDir);
    }

    @Test
    @DisplayName("Should produce both generator-schema.graphql and schema.graphql by default")
    void shouldProduceDefaultSchemas() throws Exception {
        writeSchema(schemaDir, """
                type Query {
                    hello: String
                }
                """);

        var config = createConfig(Set.of(schemaDir.toString()));
        var executor = new GenerateAllExecutor(config);

        var result = executor.execute(outputDir);

        assertThat(outputDir.resolve("generator-schema.graphql")).exists();
        assertThat(outputDir.resolve("schema.graphql")).exists();
        assertThat(result.generatorSchemaPath()).isEqualTo(outputDir.resolve("generator-schema.graphql").toString());
    }

    @Test
    @DisplayName("Should produce multiple output schemas when configured")
    void shouldProduceMultipleOutputSchemas() throws Exception {
        writeSchema(schemaDir, """
                type Query {
                    users: [User]
                }

                type User {
                    id: ID!
                    name: String
                }
                """);

        var config = createConfig(Set.of(schemaDir.toString()));
        config.setOutputSchemas(Set.of(
                new OutputSchema("api-schema.graphql"),
                new OutputSchema("internal-schema.graphql")
        ));
        var executor = new GenerateAllExecutor(config);

        executor.execute(outputDir);

        assertThat(outputDir.resolve("generator-schema.graphql")).exists();
        assertThat(outputDir.resolve("api-schema.graphql")).exists();
        assertThat(outputDir.resolve("internal-schema.graphql")).exists();
    }

    private TransformConfiguration createConfig(Set<String> schemaRootDirectories) {
        var config = new TransformConfiguration();
        config.setSchemaRootDirectories(schemaRootDirectories);
        config.setDescriptionSuffixFilename("description-suffix.md");
        config.setRemoveGeneratorDirectives(true);
        config.setRemoveFederationDefinitions(false);
        config.setExpandConnections(true);
        config.setAddFeatureFlags(false);
        return config;
    }

    private void writeSchema(Path dir, String content) throws IOException {
        Files.writeString(dir.resolve(SCHEMA_GRAPHQLS), content);
    }
}
