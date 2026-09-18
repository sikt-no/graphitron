package no.sikt.graphitron.model;

import no.sikt.graphitron.model.capture.document.GraphQLAssemblyCapture;
import no.sikt.graphitron.model.capture.document.GraphQLAstCapture;
import no.sikt.graphitron.model.capture.document.GraphQLSourceCapture;
import no.sikt.graphitron.model.capture.document.GraphitronAstCapture;
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
import static no.sikt.graphitron.model.test.SeededStore.seedGraph;
import static no.sikt.graphitron.model.test.SeededStore.withSeededStore;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * One store, two graphs, and the rules staying inside the one they are about.
 *
 * <p>Worth its own case because the conversion from a writer to a view moved where the partition is
 * enforced. A statement bound the graph as a literal and ran once per graph, so a missing predicate
 * could not reach another partition. The view selects across every graph in the store and carries
 * {@code graph_name} as a column, so an arm that drops it, or a join that matches on everything but
 * it, silently mixes two consumers together.
 *
 * <p>The exclusion is where that would hurt most and show least: one consumer's
 * {@code excludedTypes} suppressing another consumer's findings is a rule going quiet, which reads
 * exactly like a schema with nothing wrong in it. Every shadow beside this one uses a single graph
 * and cannot see it.
 */
class LintViolationScopeTest {

    private static final String MINE = "scope-mine";
    private static final String THEIRS = "scope-theirs";

    /** The same offending declaration in both graphs, so only the partition can tell them apart. */
    private static final String SDL = """
        input WidgetFilter { namePrefix: String }
        type Query { widgets: String }
        """;

    @Test
    @DisplayName("one graph's exclusion does not silence another graph's findings")
    void theExclusionStaysInsideItsGraph() {
        withSeededStore(MINE, dsl -> {
            seedGraph(dsl, THEIRS);
            capture(dsl, MINE);
            capture(dsl, THEIRS);

            // Only the first graph asks for the type to be left alone.
            dsl.insertInto(STORE_GRAPH_LINT_EXCLUDED_TYPE)
                .columns(STORE_GRAPH_LINT_EXCLUDED_TYPE.GRAPH_NAME,
                    STORE_GRAPH_LINT_EXCLUDED_TYPE.ORDINAL,
                    STORE_GRAPH_LINT_EXCLUDED_TYPE.TYPE_PATTERN,
                    STORE_GRAPH_LINT_EXCLUDED_TYPE.TOUCHED_AT)
                .values(MINE, 0, "Widget*", SEEDED_READING)
                .execute();

            // Scoped to the rule the excluded type breaks. Both graphs also break the description
            // rule, on Query as much as on the input, and the glob covers neither of those: a
            // whole-graph emptiness claim would have been about the fixture rather than the scope.
            assertThat(rulesOf(dsl, MINE))
                .as("the graph that excluded the type sees nothing about that type")
                .doesNotContain("input-object-name-suffix");
            assertThat(rulesOf(dsl, THEIRS))
                .as("the graph that excluded nothing still sees its own finding; a join matching on"
                    + " everything but the partition would have silenced this one too")
                .contains("input-object-name-suffix");
        });
    }

    private static List<String> rulesOf(DSLContext dsl, String graph) {
        return dsl.select(LINT_VIOLATION.LINT_RULE).from(LINT_VIOLATION)
            .where(LINT_VIOLATION.GRAPH_NAME.eq(graph))
            .fetch(LINT_VIOLATION.LINT_RULE);
    }

    private static void capture(DSLContext dsl, String graph) {
        Path directory = temporaryDirectory();
        try {
            Files.writeString(directory.resolve("schema.graphqls"), SDL, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        var identity = new GraphIdentity(graph, directory);
        var config = SubjectConfig.of(new SchemaRecipe(directory.resolve("pom.xml"),
            List.of(SchemaRecipe.Binding.pattern("*.graphqls")), List.of("graphqls")));
        var readAt = LocalDateTime.now();
        var documents = GraphQLSourceCapture.capture(dsl, identity, config, readAt);
        GraphQLAstCapture.capture(dsl, identity, documents, readAt);
        GraphitronAstCapture.capture(dsl, identity, documents, readAt);
        GraphQLAssemblyCapture.capture(dsl, identity, documents, readAt);
    }

    private static Path temporaryDirectory() {
        try {
            return Files.createTempDirectory("lint-scope");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
