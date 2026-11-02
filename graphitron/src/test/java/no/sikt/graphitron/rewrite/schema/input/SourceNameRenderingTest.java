package no.sikt.graphitron.rewrite.schema.input;

import graphql.schema.GraphQLObjectType;
import no.sikt.graphitron.model.schema.SchemaAssembly;
import no.sikt.graphitron.model.test.CapturedStore;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import no.sikt.graphitron.model.schema.input.SchemaInput;
import no.sikt.graphitron.model.schema.input.SchemaRecipe;
import no.sikt.graphitron.model.schema.input.SchemaSource;

/**
 * The sealed source carrier's rendering invariant, end to end because that is the only altitude at
 * which a divergence shows up. {@link SchemaSource#sourceName()} is the string the loader hands the
 * parser and the string graphql-java hands back as {@code SourceLocation.getSourceName()}, so a
 * lookup keyed on it matches byte-for-byte with no renormalisation. A divergence of one character
 * costs no compile error and no parse failure; it silently unmatches the join from a file's entry
 * to the elements it declares, so the configured tags and notes stop being applied, and capture's
 * stamp lookup with them. An equality on the carrier would catch none of that, which is why this
 * case closes the loop from a minted file arm, through the parser and capture, to the schema that
 * applies what the join found.
 */
@PipelineTier
class SourceNameRenderingTest {

    @Test
    @DisplayName("a tag and a note configured on a minted file arm land on the elements it declared")
    void attributionSurvivesTheParserRoundTripFromAMintedFileArm(@TempDir Path tmp) throws IOException {
        Path schemaDir = Files.createDirectories(tmp.resolve("nested/schema"));
        Files.writeString(schemaDir.resolve("catalog.graphqls"), """
            type Query { films: [Film!]! }
            type Film { title: String }
            """);
        // Expanded rather than hand-constructed: the invariant is about what the expansion mints, and
        // a hand-built arm would be this test agreeing with itself instead of with the producer.
        var recipe = new SchemaRecipe(null, List.of(new SchemaRecipe.Binding(
            new SchemaRecipe.Entry.Pattern("nested/**/*.graphqls"),
            Optional.of("catalog"), Optional.of("Part of the catalog feature."))),
            List.of(".graphqls"));

        var expansion = recipe.expand(tmp);
        assertThat(expansion).isInstanceOf(SchemaRecipe.Expansion.Resolved.class);
        List<SchemaInput> inputs = ((SchemaRecipe.Expansion.Resolved) expansion).matches().stream()
            .map(SchemaRecipe.Expansion.Match::input)
            .toList();
        assertThat(inputs).hasSize(1);

        GraphQLObjectType film;
        try (var store = CapturedStore.configured(tmp, inputs)) {
            var emitted = ((SchemaAssembly.Assembled) store.schema().synthesised().orElseThrow())
                .schema();
            film = (GraphQLObjectType) emitted.getType("Film");
        }
        var title = film.getFieldDefinition("title");
        assertThat(title.getDescription())
            .as("the join resolved the source name graphql-java handed back; a rendering "
                + "divergence of one character would leave this empty and fail nothing else")
            .isEqualTo("Part of the catalog feature.");
        assertThat(title.getAppliedDirectives("tag")).hasSize(1);
    }

    @Test
    @DisplayName("a file arm renders the string the expansion composes, absolute and normalized")
    void aFileArmRendersTheCanonicalString(@TempDir Path tmp) throws IOException {
        Path file = Files.createFile(tmp.resolve("a.graphqls"));

        assertThat(SchemaSource.file(tmp.resolve("./a.graphqls")).sourceName())
            .as("normalized at mint, so a minted arm and a re-expanded one render the same string")
            .isEqualTo(file.toAbsolutePath().normalize().toString());
    }

    @Test
    @DisplayName("a named arm renders its label verbatim, which is what keeps label fixtures matching")
    void aNamedArmRendersItsLabelVerbatim() {
        assertThat(SchemaSource.named("t.graphqls").sourceName()).isEqualTo("t.graphqls");
    }
}
