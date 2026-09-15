package no.sikt.graphitron.model;

import no.sikt.graphitron.model.capture.document.SdlCapture;
import no.sikt.graphitron.model.lint.LintViolations;
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

import static no.sikt.graphitron.model.Tables.LINT_VIOLATION;
import static no.sikt.graphitron.model.Tables.STORE_GRAPH_LINT_EXCLUDED_TYPE;
import static no.sikt.graphitron.model.test.SeededStore.SEEDED_READING;
import static no.sikt.graphitron.model.test.SeededStore.withSeededStore;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The findings relation as a maintained thing rather than a filled one, which is the property a
 * list of findings never had.
 *
 * <p>Three ways a finding goes away, and they are not the same mechanism. The author deletes the
 * declaration, and the entry it was written at goes with it, carrying the row off by cascade. The
 * author fixes what the rule objected to, leaving the declaration exactly where it was, and only
 * the sweep can tell that this reading no longer draws the row. The author asks for the type to be
 * left alone, and the statement never draws it in the first place.
 */
class LintViolationsTest {

    private static final String GRAPH = "lint-violations";

    private static final String OFFENDING = """
        input WidgetFilter { namePrefix: String }
        type Query { widgets(filter: WidgetFilter): String }
        """;

    private static final String COMPLIANT = """
        input WidgetFilterInput { namePrefix: String }
        type Query { widgets(filter: WidgetFilterInput): String }
        """;

    @Test
    @DisplayName("an input object whose name lacks the suffix draws a row at its declaration")
    void theRuleFiresAtTheWrittenPosition() {
        withSeededStore(GRAPH, dsl -> {
            var directory = read(dsl, OFFENDING);
            assertThat(rules(dsl))
                .as("the rule fired once, at the one offending declaration")
                .containsExactly("input-object-name-suffix");
            assertThat(dsl.select(LINT_VIOLATION.SOURCE_LINE).from(LINT_VIOLATION)
                    .where(LINT_VIOLATION.GRAPH_NAME.eq(GRAPH)).fetchSingle().value1())
                .as("at the line the declaration was written on")
                .isEqualTo(1);
            assertThat(directory).isNotNull();
        });
    }

    @Test
    @DisplayName("a compliant input object draws nothing")
    void theRuleIsSilentOnACompliantName() {
        withSeededStore(GRAPH, dsl -> {
            read(dsl, COMPLIANT);
            assertThat(rules(dsl)).isEmpty();
        });
    }

    /**
     * The property the walk could not have: an author who fixes the name leaves the declaration at
     * the same position, so nothing is deleted and nothing cascades. The row goes because this
     * reading did not restamp it.
     */
    @Test
    @DisplayName("the row goes when the author fixes the name in place")
    void theSweepRemovesAFixedViolation() {
        withSeededStore(GRAPH, dsl -> {
            Path directory = read(dsl, OFFENDING);
            assertThat(rules(dsl)).as("the finding is there to begin with").isNotEmpty();

            write(directory, COMPLIANT);
            reread(dsl, directory);

            assertThat(rules(dsl))
                .as("the declaration still exists at the same position, and the finding does not")
                .isEmpty();
        });
    }

    /**
     * The consumer's own exclusion, applied in the statement rather than after the fetch, so the
     * relation holds no row a reader would have to know to ignore.
     */
    @Test
    @DisplayName("a type the consumer excluded draws no row")
    void anExcludedTypeIsNeverDrawn() {
        withSeededStore(GRAPH, dsl -> {
            dsl.insertInto(STORE_GRAPH_LINT_EXCLUDED_TYPE)
                .columns(STORE_GRAPH_LINT_EXCLUDED_TYPE.GRAPH_NAME,
                    STORE_GRAPH_LINT_EXCLUDED_TYPE.ORDINAL,
                    STORE_GRAPH_LINT_EXCLUDED_TYPE.TYPE_PATTERN,
                    STORE_GRAPH_LINT_EXCLUDED_TYPE.TOUCHED_AT)
                .values(GRAPH, 0, "Widget*", SEEDED_READING)
                .execute();

            read(dsl, OFFENDING);

            assertThat(rules(dsl))
                .as("the glob covers the offending type, so the statement never drew it")
                .isEmpty();
        });
    }

    private static List<String> rules(DSLContext dsl) {
        return dsl.select(LINT_VIOLATION.LINT_RULE).from(LINT_VIOLATION)
            .where(LINT_VIOLATION.GRAPH_NAME.eq(GRAPH))
            .fetch(LINT_VIOLATION.LINT_RULE);
    }

    /** Captures the SDL and runs the rules over it, which is what a reading does. */
    private static Path read(DSLContext dsl, String sdl) {
        Path directory = temporaryDirectory();
        write(directory, sdl);
        reread(dsl, directory);
        return directory;
    }

    private static void reread(DSLContext dsl, Path directory) {
        var readAt = LocalDateTime.now();
        SdlCapture.captureFacts(dsl, new GraphIdentity(GRAPH, directory),
            SubjectConfig.of(new SchemaRecipe(directory.resolve("pom.xml"),
                List.of(SchemaRecipe.Binding.pattern("*.graphqls")), List.of("graphqls"))),
            readAt);
        LintViolations.write(dsl, GRAPH, readAt);
    }

    private static void write(Path directory, String sdl) {
        try {
            Files.writeString(directory.resolve("schema.graphqls"), sdl, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path temporaryDirectory() {
        try {
            return Files.createTempDirectory("lint-violations");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
