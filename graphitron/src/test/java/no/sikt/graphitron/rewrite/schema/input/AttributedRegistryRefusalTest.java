package no.sikt.graphitron.rewrite.schema.input;

import com.apollographql.federation.graphqljava.exceptions.UnsupportedFederationVersionException;
import no.sikt.graphitron.common.configuration.TestConfiguration;
import no.sikt.graphitron.model.config.RunContext;
import no.sikt.graphitron.model.diagnostics.ValidationFailedException;
import no.sikt.graphitron.model.schema.AttributedRegistry;
import no.sikt.graphitron.model.schema.input.SchemaInput;
import no.sikt.graphitron.model.schema.input.SchemaInputException;
import no.sikt.graphitron.model.schema.input.SchemaSource;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The generator's load fails on a loading-rewrite refusal exactly as it did before the rewrites
 * were composed into one function the store's assembly shares: each refusal throws the exception
 * type its rewrite always threw, with the message it always built. The mojo's catch arms and the
 * federation recipe text depend on both.
 *
 * <p>Driven through {@link AttributedRegistry#load} over files on disk, the generator's own door,
 * rather than through the rewrites one at a time, which their own tests already do.
 */
@UnitTier
class AttributedRegistryRefusalTest {

    @Test
    @DisplayName("a source two inputs claim throws SchemaInputException")
    void aSourceInTwoInputs(@TempDir Path tmp) {
        Path file = write(tmp, "claimed.graphqls", "type Query { a: String }\n");
        var input = SchemaInput.file(file);

        assertThatThrownBy(() -> load(tmp, List.of(input, input)))
            .isInstanceOf(SchemaInputException.class)
            .hasMessageContaining("is declared in two SchemaInput entries")
            .hasMessageContaining("Each source must belong to exactly one entry.");
    }

    @Test
    @DisplayName("two federation @links throw IllegalStateException naming both")
    void twoFederationLinks(@TempDir Path tmp) {
        Path file = write(tmp, "links.graphqls", """
            extend schema @link(url: "https://specs.apollo.dev/federation/v2.10", import: ["@key"])
            extend schema @link(url: "https://specs.apollo.dev/federation/v2.9", import: ["@shareable"])
            type Query { a: String }
            """);

        assertThatThrownBy(() -> load(tmp, List.of(SchemaInput.file(file))))
            .isExactlyInstanceOf(IllegalStateException.class)
            .hasMessageContaining("more than one federation @link")
            .hasMessageContaining("federation/v2.10")
            .hasMessageContaining("federation/v2.9");
    }

    @Test
    @DisplayName("an unsupported federation version throws the library's own exception")
    void anUnsupportedFederationVersion(@TempDir Path tmp) {
        Path file = write(tmp, "future.graphqls", """
            extend schema @link(url: "https://specs.apollo.dev/federation/v9.0", import: ["@key"])
            type Query { a: String }
            """);

        assertThatThrownBy(() -> load(tmp, List.of(SchemaInput.file(file))))
            .isInstanceOf(UnsupportedFederationVersionException.class);
    }

    @Test
    @DisplayName("an author's declaration of an imported directive throws the remove-it message")
    void aDeclarationCollision(@TempDir Path tmp) {
        Path file = write(tmp, "declared.graphqls", """
            directive @key(fields: String!, resolvable: Boolean) repeatable on OBJECT
            extend schema @link(url: "https://specs.apollo.dev/federation/v2.10", import: ["@key"])
            type Query { a: String }
            """);

        assertThatThrownBy(() -> load(tmp, List.of(SchemaInput.file(file))))
            .isExactlyInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Your schema declares '@key' at ")
            .hasMessageContaining("declared.graphqls:1")
            .hasMessageContaining("Remove the manual '@key' directive definition from your schema SDL.");
    }

    @Test
    @DisplayName("the federation library's v2.6 duplicate throws the library-bug message")
    void aLibraryDuplicateDeclaration(@TempDir Path tmp) {
        Path file = write(tmp, "v26.graphqls", """
            extend schema @link(url: "https://specs.apollo.dev/federation/v2.6", import: ["@tag"])
            type Query { a: String }
            """);

        assertThatThrownBy(() -> load(tmp, List.of(SchemaInput.file(file))))
            .isExactlyInstanceOf(IllegalStateException.class)
            .hasMessageContaining("federation v2.6")
            .hasMessageContaining("federation-graphql-java-support bug");
    }

    @Test
    @DisplayName("a configured tag the @link does not import throws ValidationFailedException")
    void aTagNotImported(@TempDir Path tmp) {
        Path file = write(tmp, "untagged.graphqls", """
            extend schema @link(url: "https://specs.apollo.dev/federation/v2.10", import: ["@key"])
            type Query { a: String }
            """);
        var input = new SchemaInput(SchemaSource.file(file), Optional.of("public"), Optional.empty());

        assertThatThrownBy(() -> load(tmp, List.of(input)))
            .isInstanceOf(ValidationFailedException.class)
            .satisfies(ex -> {
                var errors = ((ValidationFailedException) ex).errors();
                assertThat(errors).hasSize(1);
                assertThat(errors.getFirst().message())
                    .contains("<schemaInput tag> is configured but '@tag' is not in the @link import list")
                    .contains("untagged.graphqls:1")
                    .contains("Add \"@tag\" to the import array.");
            });
    }

    private static AttributedRegistry load(Path directory, List<SchemaInput> inputs) {
        return AttributedRegistry.load(new RunContext(inputs, directory, "refusals", directory,
            TestConfiguration.DEFAULT_OUTPUT_PACKAGE, TestConfiguration.DEFAULT_JOOQ_PACKAGE));
    }

    private static Path write(Path directory, String name, String sdl) {
        try {
            return Files.writeString(directory.resolve(name), sdl);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
