package no.sikt.graphitron.rewrite.maven;

import org.apache.maven.plugin.MojoExecutionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SchemaInputExpanderTest {

    @Test
    void singlePattern_oneMatch_returnsOneSchemaInput(@TempDir Path dir) throws Exception {
        Files.createFile(dir.resolve("schema.graphqls"));
        var binding = binding("schema.graphqls", null, null);

        var result = SchemaInputExpander.expand(List.of(binding), dir);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).sourceName())
            .isEqualTo(dir.resolve("schema.graphqls").toAbsolutePath().normalize().toString());
        assertThat(result.get(0).tag()).isEmpty();
        assertThat(result.get(0).descriptionNote()).isEmpty();
    }

    @Test
    void globPattern_multipleMatches_returnsAllWithTagAndNote(@TempDir Path dir) throws Exception {
        var sub = Files.createDirectory(dir.resolve("graphql"));
        Files.createFile(sub.resolve("a.graphqls"));
        Files.createFile(sub.resolve("b.graphqls"));
        Files.createFile(sub.resolve("c.graphqls"));
        var binding = binding("graphql/**", "mytag", "my note");

        var result = SchemaInputExpander.expand(List.of(binding), dir);

        assertThat(result).hasSize(3);
        result.forEach(si -> {
            assertThat(si.tag()).isEqualTo(Optional.of("mytag"));
            assertThat(si.descriptionNote()).isEqualTo(Optional.of("my note"));
        });
    }

    @Test
    void zeroMatchPattern_throwsMojoExecutionException(@TempDir Path dir) {
        var binding = binding("nonexistent/**/*.graphqls", null, null);

        assertThatThrownBy(() -> SchemaInputExpander.expand(List.of(binding), dir))
            .isInstanceOf(MojoExecutionException.class)
            .hasMessageContaining("nonexistent/**/*.graphqls")
            .hasMessageContaining("entry #0");
    }

    @Test
    void emptyStringTag_normalisesToEmpty(@TempDir Path dir) throws Exception {
        Files.createFile(dir.resolve("schema.graphqls"));
        var binding = binding("schema.graphqls", "", null);

        var result = SchemaInputExpander.expand(List.of(binding), dir);

        assertThat(result.get(0).tag()).isEmpty();
    }

    @Test
    void emptyStringDescriptionNote_normalisesToEmpty(@TempDir Path dir) throws Exception {
        Files.createFile(dir.resolve("schema.graphqls"));
        var binding = binding("schema.graphqls", null, "");

        var result = SchemaInputExpander.expand(List.of(binding), dir);

        assertThat(result.get(0).descriptionNote()).isEmpty();
    }

    private static SchemaInputBinding binding(String pattern, String tag, String descriptionNote) {
        var b = new SchemaInputBinding();
        b.pattern = pattern;
        b.tag = tag;
        b.descriptionNote = descriptionNote;
        return b;
    }
}
