package no.sikt.graphitron.maven;

import no.fellesstudentsystem.schema_transformer.OutputSchema;
import no.sikt.graphitron.mojo.SchemaTransformRunner;
import no.sikt.graphitron.mojo.TransformPluginConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SchemaTransformRunner - Schema transformation orchestration")
class SchemaTransformRunnerTest {
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
    @DisplayName("Should produce both generator-schema.graphql and schema.graphql when produceGeneratorSchema is true")
    void shouldProduceGeneratorAndClientSchemas() throws Exception {
        writeSchema(schemaDir, """
                type Query {
                    hello: String
                }
                """);

        var config = createConfig(Set.of(schemaDir.toString()));
        var runner = new SchemaTransformRunner(config);

        var result = runner.execute(outputDir, true);

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
        var runner = new SchemaTransformRunner(config);

        runner.execute(outputDir, true);

        assertThat(outputDir.resolve("generator-schema.graphql")).exists();
        assertThat(outputDir.resolve("api-schema.graphql")).exists();
        assertThat(outputDir.resolve("internal-schema.graphql")).exists();
    }

    @Test
    @DisplayName("Should only produce configured schemas when produceGeneratorSchema is false")
    void shouldOnlyProduceConfiguredSchemasForTransformGoal() throws Exception {
        writeSchema(schemaDir, """
                type Query {
                    hello: String
                }
                """);

        var config = createConfig(Set.of(schemaDir.toString()));
        config.setOutputSchema("my-schema.graphql");
        var runner = new SchemaTransformRunner(config);

        var result = runner.execute(outputDir, false);

        assertThat(outputDir.resolve("my-schema.graphql")).exists();
        assertThat(outputDir.resolve("generator-schema.graphql")).doesNotExist();
        assertThat(outputDir.resolve("schema.graphql")).doesNotExist();
        assertThat(result.generatorSchemaPath()).isNull();
    }

    private TransformPluginConfiguration createConfig(Set<String> schemaRootDirectories) {
        var config = new TransformPluginConfiguration();
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
