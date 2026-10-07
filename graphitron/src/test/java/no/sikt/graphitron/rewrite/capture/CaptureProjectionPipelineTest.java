package no.sikt.graphitron.rewrite.capture;

import no.sikt.graphitron.common.configuration.TestConfiguration;
import no.sikt.graphitron.rewrite.BuiltStore;
import no.sikt.graphitron.model.diagnostics.ValidationError;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static no.sikt.graphitron.model.Tables.GRAPHQL_SCHEMA_PROBLEM;
import static no.sikt.graphitron.model.Tables.GRAPHQL_TYPE;
import static no.sikt.graphitron.model.Tables.SQL_TABLE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What {@code mvn graphitron:capture} does: the gatherers fill the store and nothing else runs.
 *
 * <p>The property worth pinning is the one the command exists for. {@code validate} also fills a
 * store, but on its way to failing the build, so the only command that produced one refused to
 * produce it exactly when a reader most wants to ask what is wrong. The fixture here is a schema
 * that classifies and then fails the checks, and both halves are asserted over it: the rejection is
 * real, and the capture-only run over the same document leaves the graph in a store.
 *
 * <p>Both stores come from {@link BuiltStore}'s two arms, which build the identical context and
 * differ only in whether a pass runs after the capture.
 */
@PipelineTier
class CaptureProjectionPipelineTest {

    /**
     * A schema the checks reject over a type that otherwise classifies: the {@code @reference}
     * names no foreign key in the catalog, so {@code Film.languageName} is unclassified. Everything
     * else about the document is fine, which is what makes the facts worth capturing.
     */
    private static final String REJECTED = """
        type Film @table(name: "film") {
          languageName: String @reference(path: [{key: "no_such_fk"}])
        }
        type Query { film: Film }
        """;

    /**
     * A document no schema can be made of: {@code Film.language} names a type nothing declares.
     * Assembly refuses it, which is further than the checks ever get, so this is the case a capture
     * that ran a pass would have failed on outright.
     */
    private static final String UNASSEMBLABLE = """
        type Film @table(name: "film") {
          language: NoSuchType
        }
        type Query { film: Film }
        """;

    private static final String GRAPH = "CaptureProjectionPipelineTest";

    /** The generated catalog the fixture's {@code @table} directives reflect against. */
    private static final String JOOQ = TestConfiguration.DEFAULT_JOOQ_PACKAGE;

    @Test
    @DisplayName("a schema the checks reject is captured rather than refused")
    void captureFillsTheStoreOnASchemaTheChecksReject(@TempDir Path tmp) throws IOException {
        Path capturedRoot = tmp.resolve("captured");

        try (var captured = BuiltStore.captured(capturedRoot, GRAPH, REJECTED, JOOQ);
             var checked = BuiltStore.run(tmp.resolve("checked"), GRAPH, REJECTED, JOOQ)) {

            assertThat(checked.output().report().errors())
                .extracting(ValidationError::message)
                .as("the fixture is a rejected one, which is what makes the case below non-vacuous")
                .anyMatch(m -> m.contains("no_such_fk"));

            assertThat(captured.dsl().select(GRAPHQL_TYPE.TYPE_NAME).from(GRAPHQL_TYPE)
                .fetchSet(0, String.class))
                .as("the graph the run classified is in the store, rejection and all")
                .contains("Query", "Film");
            assertThat(captured.dsl().select(SQL_TABLE.TABLE_NAME).from(SQL_TABLE)
                .fetchSet(0, String.class))
                .as("and the catalog beside it, this run having a real jooqPackage")
                .anySatisfy(name -> assertThat(name).isEqualToIgnoringCase("film"));
        }

        // No plan, no renderers, no writer. Asserted over the capture fixture's own tree, so it is
        // this projection's silence rather than the other fixture's.
        try (Stream<Path> tree = Files.walk(capturedRoot)) {
            assertThat(tree.filter(p -> p.toString().endsWith(".java")).toList())
                .as("a capture run emits nothing")
                .isEmpty();
        }
    }

    /**
     * Capture fails only where it cannot capture. A schema assembly refuses is still read, its
     * declarations are still transcribed, and what assembly said about it is a row, which is the
     * state a developer debugging that schema wants a store in.
     */
    @Test
    @DisplayName("a schema that will not assemble is captured, and its problem is a row")
    void captureFillsTheStoreOnASchemaThatWillNotAssemble(@TempDir Path tmp) {
        try (var captured = BuiltStore.captured(tmp, GRAPH, UNASSEMBLABLE, JOOQ)) {
            assertThat(captured.dsl().select(GRAPHQL_TYPE.TYPE_NAME).from(GRAPHQL_TYPE)
                .fetchSet(0, String.class))
                .as("the declarations are transcribed whatever assembly made of them")
                .contains("Query", "Film");
            assertThat(captured.dsl()
                .select(GRAPHQL_SCHEMA_PROBLEM.STAGE, GRAPHQL_SCHEMA_PROBLEM.MESSAGE)
                .from(GRAPHQL_SCHEMA_PROBLEM).fetch(r -> r.value1() + " " + r.value2()))
                .as("and the refusal is in the store rather than in the build's exit code")
                .anyMatch(row -> row.startsWith("ASSEMBLY") && row.contains("NoSuchType"));
        }
    }
}
