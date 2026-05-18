package no.sikt.graphitron.rewrite.maven;

import org.apache.maven.plugin.MojoExecutionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SchemaInputExpanderTest {

    @Test
    void singlePattern_oneMatch_returnsOneSchemaInput(@TempDir Path dir) throws Exception {
        Files.createFile(dir.resolve("schema.graphqls"));
        var binding = binding("schema.graphqls", null, null);

        var result = SchemaInputExpander.expand(List.of(binding), dir, java.util.Set.of(".graphqls", ".graphql"));

        assertThat(result.inputs()).hasSize(1);
        assertThat(result.inputs().get(0).sourceName())
            .isEqualTo(dir.resolve("schema.graphqls").toAbsolutePath().normalize().toString());
        assertThat(result.inputs().get(0).tag()).isEmpty();
        assertThat(result.inputs().get(0).descriptionNote()).isEmpty();
        assertThat(result.emptyPatterns()).isEmpty();
    }

    @Test
    void globPattern_multipleMatches_returnsAllWithTagAndNote(@TempDir Path dir) throws Exception {
        var sub = Files.createDirectory(dir.resolve("graphql"));
        Files.createFile(sub.resolve("a.graphqls"));
        Files.createFile(sub.resolve("b.graphqls"));
        Files.createFile(sub.resolve("c.graphqls"));
        var binding = binding("graphql/**", "mytag", "my note");

        var result = SchemaInputExpander.expand(List.of(binding), dir, java.util.Set.of(".graphqls", ".graphql"));

        assertThat(result.inputs()).hasSize(3);
        result.inputs().forEach(si -> {
            assertThat(si.tag()).isEqualTo(Optional.of("mytag"));
            assertThat(si.descriptionNote()).isEqualTo(Optional.of("my note"));
        });
    }

    @Test
    void singlePatternEmpty_throwsAggregateEmpty(@TempDir Path dir) {
        var binding = binding("nonexistent/**/*.graphqls", null, null);

        assertThatThrownBy(() -> SchemaInputExpander.expand(List.of(binding), dir, java.util.Set.of(".graphqls", ".graphql")))
            .isInstanceOf(MojoExecutionException.class)
            .hasMessageContaining("<schemaInputs> matched no files")
            .hasMessageContaining("nonexistent/**/*.graphqls")
            .hasMessageContaining("entry #0");
    }

    @Test
    void emptyStringTag_normalisesToEmpty(@TempDir Path dir) throws Exception {
        Files.createFile(dir.resolve("schema.graphqls"));
        var binding = binding("schema.graphqls", "", null);

        var result = SchemaInputExpander.expand(List.of(binding), dir, java.util.Set.of(".graphqls", ".graphql"));

        assertThat(result.inputs().get(0).tag()).isEmpty();
    }

    @Test
    void emptyStringDescriptionNote_normalisesToEmpty(@TempDir Path dir) throws Exception {
        Files.createFile(dir.resolve("schema.graphqls"));
        var binding = binding("schema.graphqls", null, "");

        var result = SchemaInputExpander.expand(List.of(binding), dir, java.util.Set.of(".graphqls", ".graphql"));

        assertThat(result.inputs().get(0).descriptionNote()).isEmpty();
    }

    @Test
    void expand_filtersFilesNotMatchingConfiguredExtensions(@TempDir Path dir) throws Exception {
        Files.createFile(dir.resolve("schema.graphqls"));
        Files.createFile(dir.resolve("README.md"));
        var binding = binding("**/*", null, null);

        var result = SchemaInputExpander.expand(List.of(binding), dir, Set.of(".graphqls"));

        assertThat(result.inputs()).hasSize(1);
        assertThat(result.inputs().get(0).sourceName())
            .isEqualTo(dir.resolve("schema.graphqls").toAbsolutePath().normalize().toString());
    }

    @Test
    void expand_dotGraphqlAccepted(@TempDir Path dir) throws Exception {
        Files.createFile(dir.resolve("schema.graphqls"));
        Files.createFile(dir.resolve("extras.graphql"));
        var binding = binding("**/*", null, null);

        var result = SchemaInputExpander.expand(List.of(binding), dir, Set.of(".graphqls", ".graphql"));

        assertThat(result.inputs()).extracting(si -> Path.of(si.sourceName()).getFileName().toString())
            .containsExactlyInAnyOrder("schema.graphqls", "extras.graphql");
    }

    @Test
    void expand_zeroMatchAfterExtensionFilter_throwsMojoExecutionException(@TempDir Path dir) throws Exception {
        Files.createFile(dir.resolve("README.md"));
        var binding = binding("**/*", null, null);

        assertThatThrownBy(() -> SchemaInputExpander.expand(List.of(binding), dir, Set.of(".graphqls")))
            .isInstanceOf(MojoExecutionException.class)
            .hasMessageContaining("<schemaInputs> matched no files")
            .hasMessageContaining("entry #0");
    }

    @Test
    void multiplePatterns_oneEmpty_warnsAndContinues(@TempDir Path dir) throws Exception {
        var stable = Files.createDirectory(dir.resolve("stable"));
        Files.createFile(stable.resolve("a.graphqls"));
        Files.createDirectory(dir.resolve("beta"));
        var experimental = Files.createDirectory(dir.resolve("experimental"));
        Files.createFile(experimental.resolve("c.graphqls"));

        var result = SchemaInputExpander.expand(
            List.of(
                binding("stable/**/*.graphqls", "stable", null),
                binding("beta/**/*.graphqls", "beta", null),
                binding("experimental/**/*.graphqls", "experimental", null)
            ),
            dir,
            Set.of(".graphqls", ".graphql"));

        assertThat(result.inputs())
            .extracting(si -> Path.of(si.sourceName()).getFileName().toString())
            .containsExactlyInAnyOrder("a.graphqls", "c.graphqls");
        assertThat(result.emptyPatterns()).hasSize(1);
        assertThat(result.emptyPatterns().get(0).entryIndex()).isEqualTo(1);
        assertThat(result.emptyPatterns().get(0).pattern()).isEqualTo("beta/**/*.graphqls");
    }

    @Test
    void multiplePatterns_oneEmpty_afterExtensionFilter_warnsAndContinues(@TempDir Path dir) throws Exception {
        var stable = Files.createDirectory(dir.resolve("stable"));
        Files.createFile(stable.resolve("a.graphqls"));
        var beta = Files.createDirectory(dir.resolve("beta"));
        Files.createFile(beta.resolve("description-suffix.md"));

        var result = SchemaInputExpander.expand(
            List.of(
                binding("stable/**/*", "stable", null),
                binding("beta/**/*", "beta", null)
            ),
            dir,
            Set.of(".graphqls", ".graphql"));

        assertThat(result.inputs())
            .extracting(si -> Path.of(si.sourceName()).getFileName().toString())
            .containsExactly("a.graphqls");
        assertThat(result.emptyPatterns()).hasSize(1);
        assertThat(result.emptyPatterns().get(0).entryIndex()).isEqualTo(1);
        assertThat(result.emptyPatterns().get(0).pattern()).isEqualTo("beta/**/*");
    }

    @Test
    void allPatternsEmpty_throwsAggregateEmpty(@TempDir Path dir) {
        var b1 = binding("alpha/**/*.graphqls", null, null);
        var b2 = binding("bravo/**/*.graphqls", null, null);

        assertThatThrownBy(() -> SchemaInputExpander.expand(List.of(b1, b2), dir, Set.of(".graphqls", ".graphql")))
            .isInstanceOf(MojoExecutionException.class)
            .hasMessageContaining("<schemaInputs> matched no files")
            .hasMessageContaining("entry #0")
            .hasMessageContaining("alpha/**/*.graphqls")
            .hasMessageContaining("entry #1")
            .hasMessageContaining("bravo/**/*.graphqls");
    }

    private static SchemaInputBinding binding(String pattern, String tag, String descriptionNote) {
        var b = new SchemaInputBinding();
        b.pattern = pattern;
        b.tag = tag;
        b.descriptionNote = descriptionNote;
        return b;
    }
}
