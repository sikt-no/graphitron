package no.sikt.graphitron.rewrite.schema.input;

import no.sikt.graphitron.rewrite.schema.RewriteSchemaLoader;
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

/**
 * The sealed source carrier's rendering invariant, end to end because that is the only altitude at
 * which a divergence shows up. {@link SchemaSource#sourceName()} is the string the loader hands the
 * parser and the string graphql-java hands back as {@code SourceLocation.getSourceName()}, so a
 * lookup keyed on it matches byte-for-byte with no renormalisation. A divergence of one character
 * costs no compile error and no parse failure; it silently stops tags and description notes from
 * being applied, and silently unmatches capture's stamp lookup. An equality on the carrier would
 * catch none of that, which is why this case closes the loop from a minted file arm, through the
 * parser, to the appliers that read what came back.
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

        var bySource = SchemaInputAttribution.build(inputs);
        var registry = RewriteSchemaLoader.load(List.of((SchemaSource.File) inputs.getFirst().source()));
        TagApplier.apply(registry, bySource);
        DescriptionNoteApplier.apply(registry, bySource);

        var film = registry.getTypeOrNull("Film", graphql.language.ObjectTypeDefinition.class);
        assertThat(film).isNotNull();
        assertThat(film.getFieldDefinitions().getFirst().getDirectives())
            .as("the tag applier resolved the source name graphql-java handed back; a rendering "
                + "divergence of one character would leave this empty and fail nothing else")
            .extracting(graphql.language.Directive::getName)
            .contains("tag");
        assertThat(film.getFieldDefinitions().getFirst().getDescription()).isNotNull();
        assertThat(film.getFieldDefinitions().getFirst().getDescription().getContent())
            .contains("Part of the catalog feature.");
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
