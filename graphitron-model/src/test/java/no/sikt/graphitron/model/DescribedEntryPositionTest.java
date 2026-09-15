package no.sikt.graphitron.model;

import no.sikt.graphitron.model.capture.document.SdlCapture;
import no.sikt.graphitron.model.run.GraphIdentity;
import no.sikt.graphitron.model.run.SubjectConfig;
import no.sikt.graphitron.model.schema.input.SchemaRecipe;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_FIELD_DEFINITION_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_TYPE_DECLARATION_ENTRY;
import static no.sikt.graphitron.model.test.SeededStore.withSeededStore;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Where a described declaration's position lands, which is not where a reader would guess and which
 * anything computing an edit against a name has to know.
 *
 * <p>A declaration carrying a description is positioned at the description rather than at the token
 * it describes, the parser treating the description as the first part of the node. So the position
 * of a documented type is the opening quote of its docstring, one or more lines above the word
 * {@code type}. An undocumented one is positioned at its own first token, which is where the name
 * is.
 *
 * <p>This is pinned because it decides what a producer may compute from a position alone. Finding
 * the node is unaffected, an editor asked to jump lands on the declaration either way. Replacing
 * the name is not: an edit spanning the name's length from this position would overwrite the start
 * of the docstring, so a rule offering a rename has to withhold it where a description is present,
 * and the presence of one is a column rather than something to be inferred from the position.
 */
class DescribedEntryPositionTest {

    private static final String GRAPH = "described-entry-position";

    /** Two described declarations and one bare one, each on a line this test can name. */
    private static final String SDL = """
        "A widget."
        type Widget {
          "Its name."
          widgetName: String
          plain: String
        }
        """;

    @Test
    @DisplayName("a described declaration is positioned at its description, a bare one at its name")
    void theDescriptionCarriesThePosition() {
        withSeededStore(GRAPH, dsl -> {
            read(dsl, fixture());

            var t = GRAPHQL_AST_TYPE_DECLARATION_ENTRY;
            var type = dsl.select(t.SOURCE_LINE, t.DESCRIPTION).from(t)
                .where(t.GRAPH_NAME.eq(GRAPH), t.NAME.eq("Widget")).fetchSingle();
            assertThat(type.value2()).as("the fixture documents the type").isNotNull();
            assertThat(type.value1())
                .as("the docstring's line, not the line the word type is on")
                .isEqualTo(1);

            assertThat(fieldLine(dsl, "widgetName"))
                .as("a described field is positioned at its docstring")
                .isEqualTo(3);
            assertThat(fieldLine(dsl, "plain"))
                .as("a bare field is positioned at its own name")
                .isEqualTo(5);
        });
    }

    private static int fieldLine(DSLContext dsl, String fieldName) {
        var f = GRAPHQL_AST_FIELD_DEFINITION_ENTRY;
        return dsl.select(f.SOURCE_LINE).from(f)
            .where(f.GRAPH_NAME.eq(GRAPH), f.TYPE_NAME.eq("Widget"), f.NAME.eq(fieldName))
            .fetchSingle().value1();
    }

    private static Path fixture() {
        Path directory = temporaryDirectory();
        try {
            Files.writeString(directory.resolve("schema.graphqls"), SDL, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return directory;
    }

    private static void read(DSLContext dsl, Path baseDir) {
        SdlCapture.captureFacts(dsl, new GraphIdentity(GRAPH, baseDir),
            SubjectConfig.of(new SchemaRecipe(baseDir.resolve("pom.xml"),
                List.of(SchemaRecipe.Binding.pattern("*.graphqls")), List.of("graphqls"))),
            LocalDateTime.now());
    }

    private static Path temporaryDirectory() {
        try {
            return Files.createTempDirectory("described-entry-position");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
