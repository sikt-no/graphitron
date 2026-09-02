package no.sikt.graphitron.rewrite.schema.input;

import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import no.sikt.graphitron.model.schema.input.SchemaInput;
import no.sikt.graphitron.model.schema.input.SchemaRecipe;
import no.sikt.graphitron.model.schema.input.SchemaSource;

/**
 * The one glob dialect and its typed result. Retargeted from the Maven plugin's own expander, which
 * dissolved: the walk, the extension filter, the per-entry attribution and the empty-pattern
 * observations are core, and only their rendering as author-facing prose is the plugin's.
 */
@UnitTier
class SchemaRecipeExpansionTest {

    private static final List<String> BOTH = List.of(".graphqls", ".graphql");

    @Test
    void singlePattern_oneMatch_returnsOneInput(@TempDir Path dir) throws Exception {
        Files.createFile(dir.resolve("schema.graphqls"));

        var resolved = resolve(recipe(BOTH, SchemaRecipe.Binding.pattern("schema.graphqls")), dir);

        assertThat(resolved.matches()).hasSize(1);
        assertThat(resolved.matches().getFirst().input().sourceName())
            .isEqualTo(dir.resolve("schema.graphqls").toAbsolutePath().normalize().toString());
        assertThat(resolved.matches().getFirst().input().tag()).isEmpty();
        assertThat(resolved.matches().getFirst().input().descriptionNote()).isEmpty();
        assertThat(resolved.emptyPatterns()).isEmpty();
    }

    @Test
    void globPattern_multipleMatches_carryTagAndNote(@TempDir Path dir) throws Exception {
        var sub = Files.createDirectory(dir.resolve("graphql"));
        Files.createFile(sub.resolve("a.graphqls"));
        Files.createFile(sub.resolve("b.graphqls"));
        Files.createFile(sub.resolve("c.graphqls"));

        var resolved = resolve(recipe(BOTH, new SchemaRecipe.Binding(
            new SchemaRecipe.Entry.Pattern("graphql/**"),
            Optional.of("mytag"), Optional.of("my note"))), dir);

        assertThat(resolved.matches()).hasSize(3);
        resolved.matches().forEach(match -> {
            assertThat(match.input().tag()).isEqualTo(Optional.of("mytag"));
            assertThat(match.input().descriptionNote()).isEqualTo(Optional.of("my note"));
        });
    }

    @Test
    void filtersFilesNotMatchingConfiguredExtensions(@TempDir Path dir) throws Exception {
        Files.createFile(dir.resolve("schema.graphqls"));
        Files.createFile(dir.resolve("README.md"));

        var resolved = resolve(recipe(List.of(".graphqls"),
            SchemaRecipe.Binding.pattern("**/*")), dir);

        assertThat(resolved.matches()).hasSize(1);
        assertThat(resolved.matches().getFirst().input().sourceName())
            .isEqualTo(dir.resolve("schema.graphqls").toAbsolutePath().normalize().toString());
    }

    @Test
    void dotGraphqlAccepted(@TempDir Path dir) throws Exception {
        Files.createFile(dir.resolve("schema.graphqls"));
        Files.createFile(dir.resolve("extras.graphql"));

        var resolved = resolve(recipe(BOTH, SchemaRecipe.Binding.pattern("**/*")), dir);

        assertThat(resolved.matches())
            .extracting(m -> Path.of(m.input().sourceName()).getFileName().toString())
            .containsExactlyInAnyOrder("schema.graphqls", "extras.graphql");
    }

    @Test
    @DisplayName("a pattern that matched nothing is an observation, not a failure, while others matched")
    void multiplePatterns_oneEmpty_isObservedAndTheRestResolve(@TempDir Path dir) throws Exception {
        var stable = Files.createDirectory(dir.resolve("stable"));
        Files.createFile(stable.resolve("a.graphqls"));
        Files.createDirectory(dir.resolve("beta"));
        var experimental = Files.createDirectory(dir.resolve("experimental"));
        Files.createFile(experimental.resolve("c.graphqls"));

        var resolved = resolve(recipe(BOTH,
            SchemaRecipe.Binding.pattern("stable/**/*.graphqls"),
            SchemaRecipe.Binding.pattern("beta/**/*.graphqls"),
            SchemaRecipe.Binding.pattern("experimental/**/*.graphqls")), dir);

        assertThat(resolved.matches())
            .extracting(m -> Path.of(m.input().sourceName()).getFileName().toString())
            .containsExactlyInAnyOrder("a.graphqls", "c.graphqls");
        assertThat(resolved.emptyPatterns()).hasSize(1);
        assertThat(resolved.emptyPatterns().getFirst().entryIndex()).isEqualTo(1);
        assertThat(resolved.emptyPatterns().getFirst().pattern()).isEqualTo("beta/**/*.graphqls");
    }

    @Test
    @DisplayName("emptied by the extension filter is the same observation as emptied by the glob")
    void multiplePatterns_oneEmptyAfterExtensionFilter_isObserved(@TempDir Path dir) throws Exception {
        var stable = Files.createDirectory(dir.resolve("stable"));
        Files.createFile(stable.resolve("a.graphqls"));
        var beta = Files.createDirectory(dir.resolve("beta"));
        Files.createFile(beta.resolve("description-suffix.md"));

        var resolved = resolve(recipe(BOTH,
            SchemaRecipe.Binding.pattern("stable/**/*"),
            SchemaRecipe.Binding.pattern("beta/**/*")), dir);

        assertThat(resolved.matches())
            .extracting(m -> Path.of(m.input().sourceName()).getFileName().toString())
            .containsExactly("a.graphqls");
        assertThat(resolved.emptyPatterns()).hasSize(1);
        assertThat(resolved.emptyPatterns().getFirst().entryIndex()).isEqualTo(1);
        assertThat(resolved.emptyPatterns().getFirst().pattern()).isEqualTo("beta/**/*");
    }

    @Test
    @DisplayName("every pattern empty is its own arm, carrying every observation")
    void allPatternsEmpty_isTheNoMatchesArm(@TempDir Path dir) {
        var expansion = recipe(BOTH,
            SchemaRecipe.Binding.pattern("alpha/**/*.graphqls"),
            SchemaRecipe.Binding.pattern("bravo/**/*.graphqls")).expand(dir);

        assertThat(expansion).isInstanceOf(SchemaRecipe.Expansion.NoMatches.class);
        assertThat(((SchemaRecipe.Expansion.NoMatches) expansion).emptyPatterns())
            .extracting(SchemaRecipe.Expansion.EmptyPattern::entryIndex,
                SchemaRecipe.Expansion.EmptyPattern::pattern)
            .containsExactly(
                org.assertj.core.groups.Tuple.tuple(0, "alpha/**/*.graphqls"),
                org.assertj.core.groups.Tuple.tuple(1, "bravo/**/*.graphqls"));
    }

    @Test
    @DisplayName("a file literal re-expands by identity, and drops out once it stops resolving")
    void fileLiteralReExpandsByIdentityAndIsLostWhenDeleted(@TempDir Path dir) throws Exception {
        Path file = Files.createFile(dir.resolve("literal.graphqls"));
        var recipe = recipe(BOTH, SchemaRecipe.Binding.literal(SchemaSource.file(file)));

        assertThat(resolve(recipe, dir).matches())
            .extracting(m -> m.input().sourceName())
            .containsExactly(file.toAbsolutePath().normalize().toString());

        Files.delete(file);
        assertThat(recipe.expand(dir))
            .as("a literal file that stopped resolving is a lost match, exactly as a shrunk glob is")
            .isInstanceOf(SchemaRecipe.Expansion.NoMatches.class);
    }

    @Test
    @DisplayName("a literal bypasses the extension filter, as a programmatic input list does")
    void literalBypassesTheExtensionFilter(@TempDir Path dir) throws Exception {
        Path odd = Files.createFile(dir.resolve("schema.sdl"));

        assertThat(resolve(recipe(List.of(".graphqls"),
            SchemaRecipe.Binding.literal(SchemaSource.file(odd))), dir).matches())
            .hasSize(1);
    }

    @Test
    @DisplayName("the currency projection holds pattern matches and file literals, never named ones")
    void currencyProjectionExcludesNamedLiteralsOnly(@TempDir Path dir) throws Exception {
        Files.createFile(dir.resolve("globbed.graphqls"));
        Path literal = Files.createFile(dir.resolve("literal.graphqls"));

        var resolved = resolve(recipe(BOTH,
            SchemaRecipe.Binding.pattern("globbed.graphqls"),
            SchemaRecipe.Binding.literal(SchemaSource.file(literal)),
            SchemaRecipe.Binding.literal(SchemaSource.named("a-bare-label"))), dir);

        assertThat(resolved.matches())
            .as("the full ordered match list, in configuration order, is the round trip's subject")
            .extracting(SchemaRecipe.Expansion.Match::entryIndex)
            .containsExactly(0, 1, 2);
        assertThat(resolved.currencyRelevantMatches())
            .as("the projection a currency verdict may range over: a label re-expands to itself, so "
                + "counting it would make every programmatic graph trivially current")
            .extracting(SchemaRecipe.Expansion.Match::entryIndex)
            .containsExactly(0, 1);
    }

    @Test
    @DisplayName("a literal recipe over an input list is that list, entry for entry")
    void literalOverAnInputListRoundTripsThroughExpansion(@TempDir Path dir) throws Exception {
        Path file = Files.createFile(dir.resolve("one.graphqls"));
        var inputs = List.of(
            new SchemaInput(SchemaSource.file(file), Optional.of("t"), Optional.of("n")),
            SchemaInput.named("label"));

        var resolved = resolve(SchemaRecipe.literalOver(inputs, Set.copyOf(BOTH)), dir);

        assertThat(resolved.matches()).extracting(SchemaRecipe.Expansion.Match::input)
            .containsExactlyElementsOf(inputs);
    }

    private static SchemaRecipe recipe(List<String> extensions, SchemaRecipe.Binding... bindings) {
        return new SchemaRecipe(null, List.of(bindings), extensions);
    }

    private static SchemaRecipe.Expansion.Resolved resolve(SchemaRecipe recipe, Path baseDir) {
        var expansion = recipe.expand(baseDir);
        assertThat(expansion).isInstanceOf(SchemaRecipe.Expansion.Resolved.class);
        return (SchemaRecipe.Expansion.Resolved) expansion;
    }
}
