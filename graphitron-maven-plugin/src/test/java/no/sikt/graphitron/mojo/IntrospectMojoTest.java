package no.sikt.graphitron.mojo;

import com.fasterxml.jackson.databind.ObjectMapper;
import no.sikt.graphitron.mojo.lsp.LspConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class IntrospectMojoTest {

    @TempDir
    Path tempDir;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("Generated LSP config has valid JSON structure")
    void generatedConfigHasValidStructure() throws IOException {
        // Given: A sample LSP config
        var config = new LspConfig(
                java.util.List.of(
                        new LspConfig.TableConfig(
                                "FILM",
                                "",
                                new LspConfig.TableDefinition("/tables/FILM", 1, 1),
                                java.util.List.of(
                                        new LspConfig.TableReference("LANGUAGE", "FILM__FILM_LANGUAGE_ID_FKEY", false)
                                ),
                                java.util.List.of()
                        )
                ),
                java.util.List.of()
        );

        // When: Writing to JSON
        var outputPath = tempDir.resolve("test-config.json");
        MAPPER.writeValue(outputPath.toFile(), config);

        // Then: File exists and can be parsed back
        assertThat(outputPath).exists();
        var content = Files.readString(outputPath);
        assertThat(content).contains("\"table_name\"");
        assertThat(content).contains("\"FILM\"");
        assertThat(content).contains("\"inverse\"");

        // And: Can be deserialized
        var parsed = MAPPER.readValue(outputPath.toFile(), LspConfig.class);
        assertThat(parsed.tables()).hasSize(1);
        assertThat(parsed.tables().get(0).tableName()).isEqualTo("FILM");
    }

    @Test
    @DisplayName("TableReference correctly represents FK direction")
    void tableReferenceDirection() {
        // Given: Outgoing reference (this table owns FK)
        var outgoing = new LspConfig.TableReference("LANGUAGE", "FILM__FILM_LANGUAGE_ID_FKEY", false);

        // Given: Incoming reference (other table owns FK)
        var incoming = new LspConfig.TableReference("FILM_ACTOR", "FILM_ACTOR__FILM_ACTOR_FILM_ID_FKEY", true);

        // Then: Directions are correctly set
        assertThat(outgoing.inverse()).isFalse();
        assertThat(incoming.inverse()).isTrue();
    }
}
