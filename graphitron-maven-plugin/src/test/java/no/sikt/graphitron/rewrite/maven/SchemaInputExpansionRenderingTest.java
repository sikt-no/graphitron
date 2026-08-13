package no.sikt.graphitron.rewrite.maven;

import no.sikt.graphitron.rewrite.schema.input.SchemaRecipe;
import org.apache.maven.plugin.MojoExecutionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The mojo-side half of what the dissolved {@code SchemaInputExpander} used to own: the
 * {@code <schemaInputs>} bean decode, and the rendering of the core expansion's typed result as
 * author-facing prose. The walk itself, its extension filter and its per-entry observations moved to
 * {@code SchemaRecipeExpansionTest} beside the dialect.
 *
 * <p>The three throwing cases all hold the <em>aggregate</em>-empty arm and differ by the path that
 * empties the set rather than by the message; ported as anything but three aggregate-empty pins, the
 * extension-filter path would lose its only cover.
 */
class SchemaInputExpansionRenderingTest {

    @Test
    void singlePatternEmpty_throwsAggregateEmpty(@TempDir Path dir) {
        var mojo = mojoWith(binding("nonexistent/**/*.graphqls", null, null));

        assertThatThrownBy(() -> expand(mojo, dir, Set.of(".graphqls", ".graphql")))
            .isInstanceOf(MojoExecutionException.class)
            .hasMessageContaining("<schemaInputs> matched no files")
            .hasMessageContaining("nonexistent/**/*.graphqls")
            .hasMessageContaining("entry #0");
    }

    @Test
    @DisplayName("a glob that matched before the extension filter dropped everything reads the same")
    void zeroMatchAfterExtensionFilter_throwsAggregateEmpty(@TempDir Path dir) throws Exception {
        Files.createFile(dir.resolve("README.md"));
        var mojo = mojoWith(binding("**/*", null, null));

        assertThatThrownBy(() -> expand(mojo, dir, Set.of(".graphqls")))
            .isInstanceOf(MojoExecutionException.class)
            .hasMessageContaining("<schemaInputs> matched no files")
            .hasMessageContaining("entry #0");
    }

    @Test
    @DisplayName("several bindings missing at once enumerate one line each, which is what an author reads")
    void allPatternsEmpty_throwsAggregateEmpty(@TempDir Path dir) {
        var mojo = mojoWith(
            binding("alpha/**/*.graphqls", null, null),
            binding("bravo/**/*.graphqls", null, null));

        assertThatThrownBy(() -> expand(mojo, dir, Set.of(".graphqls", ".graphql")))
            .isInstanceOf(MojoExecutionException.class)
            .hasMessageContaining("<schemaInputs> matched no files")
            .hasMessageContaining("entry #0")
            .hasMessageContaining("alpha/**/*.graphqls")
            .hasMessageContaining("entry #1")
            .hasMessageContaining("bravo/**/*.graphqls");
    }

    @Test
    void scannerTrouble_namesTheFailingEntry(@TempDir Path dir) {
        // The plexus scanner refuses a base directory that is not one, which is the cheapest way to
        // reach the arm; what is pinned is that the failing entry's index and pattern survive into
        // the message, which a propagated RuntimeException would have lost.
        var mojo = mojoWith(binding("**/*.graphqls", null, null));

        assertThatThrownBy(() -> expand(mojo, dir.resolve("no-such-directory"), Set.of(".graphqls")))
            .isInstanceOf(MojoExecutionException.class)
            .hasMessageContaining("scanner error (entry #0)")
            .hasMessageContaining("**/*.graphqls");
    }

    @Test
    void emptyStringTag_normalisesToAbsent(@TempDir Path dir) throws Exception {
        Files.createFile(dir.resolve("schema.graphqls"));
        var mojo = mojoWith(binding("schema.graphqls", "", null));

        assertThat(expand(mojo, dir, Set.of(".graphqls")).getFirst().tag()).isEmpty();
    }

    @Test
    void emptyStringDescriptionNote_normalisesToAbsent(@TempDir Path dir) throws Exception {
        Files.createFile(dir.resolve("schema.graphqls"));
        var mojo = mojoWith(binding("schema.graphqls", null, ""));

        assertThat(expand(mojo, dir, Set.of(".graphqls")).getFirst().descriptionNote()).isEmpty();
    }

    @Test
    void configuredTagAndNoteReachTheExpandedInputs(@TempDir Path dir) throws Exception {
        Files.createFile(dir.resolve("schema.graphqls"));
        var mojo = mojoWith(binding("schema.graphqls", "mytag", "my note"));

        var inputs = expand(mojo, dir, Set.of(".graphqls"));

        assertThat(inputs).hasSize(1);
        assertThat(inputs.getFirst().tag()).isEqualTo(Optional.of("mytag"));
        assertThat(inputs.getFirst().descriptionNote()).isEqualTo(Optional.of("my note"));
    }

    @Test
    @DisplayName("nothing configured is not the same as configured patterns matching nothing")
    void noBindingsAtAllExpandsToNothingWithoutFailing(@TempDir Path dir) throws Exception {
        var mojo = new GenerateMojo();

        assertThat(expand(mojo, dir, Set.of(".graphqls"))).isEmpty();
    }

    /** The one seam {@code buildContext} runs: the bean decode, then the core expansion. */
    private static List<no.sikt.graphitron.rewrite.schema.input.SchemaInput> expand(
            AbstractRewriteMojo mojo, Path basedir, Set<String> extensions)
            throws MojoExecutionException {
        SchemaRecipe recipe = mojo.buildSchemaRecipe(extensions);
        return mojo.expandRecipe(recipe, basedir);
    }

    private static AbstractRewriteMojo mojoWith(SchemaInputBinding... bindings) {
        var mojo = new GenerateMojo();
        mojo.schemaInputs = List.of(bindings);
        return mojo;
    }

    private static SchemaInputBinding binding(String pattern, String tag, String descriptionNote) {
        var b = new SchemaInputBinding();
        b.pattern = pattern;
        b.tag = tag;
        b.descriptionNote = descriptionNote;
        return b;
    }
}
