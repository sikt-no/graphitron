package no.sikt.graphitron.rewrite.capture;

import no.sikt.graphitron.model.run.GraphitronStore;
import no.sikt.graphitron.model.boot.GraphitronModelStore;
import no.sikt.graphitron.rewrite.GraphQLRewriteGenerator;
import no.sikt.graphitron.model.config.RunContext;
import no.sikt.graphitron.model.diagnostics.SchemaParseException;
import no.sikt.graphitron.model.schema.input.SchemaInput;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static no.sikt.graphitron.model.Tables.GRAPHQL_SCHEMA_PROBLEM;
import static no.sikt.graphitron.model.Tables.GRAPHQL_TYPE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The pipeline's own ordering property: a run whose schema a stage refused still fills the store
 * before it fails.
 *
 * <p>This is the behaviour the reading stages were split for, and it is a property of the pipeline
 * rather than of the writer, which is why it is pinned here and not only in the capture agreement
 * anchors. Before the split, one unparseable file meant no registry at all, so the run threw at the
 * loader and the store learned nothing: the workspace lost every fact about every other file, and an
 * editor reading those facts had nothing to say about the very buffer being edited. Now every stage
 * runs over what survived the last one and the failure is pronounced afterwards.
 *
 * <p>The run still fails, and with the same exception it always threw. The change is what is true of
 * the store by the time it does.
 *
 * <p><b>Half of this property is unpinned here, and deliberately so.</b> What each stage refused
 * is recorded in {@code graphql_schema_problem}, written by the document gatherer, which a real
 * build now reaches on this path: both mojos capture the model before the pass. These cases drive
 * the generator directly rather than through a mojo, so they run the walk and nothing else, which
 * is why they assert the surviving half, that a broken file costs its own declarations and no
 * others, and say nothing about the verdict. What pins the verdict is
 * no.sikt.graphitron.model.GraphQLSchemaProblemsTest, against the gatherer itself, over a corpus that
 * provokes each of the three stages.
 */
@PipelineTier
class BrokenSourceStillCapturesPipelineTest {

    @Test
    @DisplayName("a run refused by the parser keeps the last clean reading and records the verdict")
    void aBrokenSourceKeepsTheLastCleanReading(@TempDir Path tmp) throws IOException {
        Path schemaDir = Files.createDirectories(tmp.resolve("schema"));
        Path good = schemaDir.resolve("good.graphqls");
        Files.writeString(good, """
            type Query { film: Film }
            type Film { title: String }
            """);
        Path broken = schemaDir.resolve("broken.graphqls");
        Files.writeString(broken, "type Actor { name: String }\n");

        Path storeDir = Files.createDirectories(tmp.resolve("store"));
        var ctx = context(tmp, storeDir, List.of(SchemaInput.file(good), SchemaInput.file(broken)));
        try (var store = GraphitronStore.captured(ctx)) {
            assertThat(store.store().dsl().fetchCount(GRAPHQL_TYPE, GRAPHQL_TYPE.TYPE_NAME.eq("Actor")))
                .as("the clean reading the broken one meets")
                .isOne();
        }

        Files.writeString(broken, """
            type Actor { name: String }
            strayTokenHere
            """);
        assertThatThrownBy(() -> {
            try (var store = GraphitronStore.captured(ctx)) {
                new GraphQLRewriteGenerator(ctx, store).validate();
            }
        })
            .as("the run fails at the capture, with the exception the mojo's catch arm handles")
            .isInstanceOf(SchemaParseException.class)
            .hasMessageContaining(broken.toString());

        try (var store = GraphitronModelStore.openAt(storeDir)) {
            assertThat(store.location()).as("the fixture's store is the shared file, not a fallback")
                .isPresent();

            assertThat(store.dsl().select(GRAPHQL_TYPE.TYPE_NAME).from(GRAPHQL_TYPE)
                .fetchSet(0, String.class))
                .as("the reading ended at the parse, so the store still states the clean one")
                .contains("Query", "Film", "Actor");
            assertThat(store.dsl().fetchCount(GRAPHQL_SCHEMA_PROBLEM,
                    GRAPHQL_SCHEMA_PROBLEM.SOURCE_NAME.eq(broken.toString())))
                .as("and the refusal is recorded, which is what makes the facts Previous")
                .isPositive();
        }
    }

    @Test
    @DisplayName("a run whose schema will not assemble still captures what it declared")
    void anUnassemblableSchemaStillCapturesItsDeclarations(@TempDir Path tmp) throws IOException {
        Path schemaDir = Files.createDirectories(tmp.resolve("schema"));
        Path dangling = schemaDir.resolve("dangling.graphqls");
        // Parses, and the registry admits it; only assembly can see that Nope resolves to nothing,
        // which is the whole reason assembly runs even on a pass with no use for its output.
        Files.writeString(dangling, "type Query { gone: Nope }\n");

        Path storeDir = Files.createDirectories(tmp.resolve("store"));
        var ctx = context(tmp, storeDir, List.of(SchemaInput.file(dangling)));

        try (var store = GraphitronStore.captured(ctx)) {
            assertThatThrownBy(() -> new GraphQLRewriteGenerator(ctx, store).validate())
                .as("an unassemblable schema still fails the run")
                .isInstanceOf(RuntimeException.class);
        }

        try (var store = GraphitronModelStore.openAt(storeDir)) {
            assertThat(store.dsl().select(GRAPHQL_TYPE.TYPE_NAME).from(GRAPHQL_TYPE)
                .fetchSet(0, String.class))
                .as("the document parsed and the registry admitted it, so capture ran; only "
                    + "assembly could see that Nope resolves to nothing, and it ran after")
                .contains("Query");
        }
    }

    /**
     * The third stage's refusal: the loading rewrites. Capture records it and carries on, and the run
     * still fails, saying what capture recorded. Each rewrite's own tests pin its message.
     */
    @Test
    @DisplayName("a run the loading rewrites refuse still fails, saying what capture recorded")
    void aLoadingRewriteRefusalStillFailsTheRun(@TempDir Path tmp) throws IOException {
        Path schemaDir = Files.createDirectories(tmp.resolve("schema"));
        Path links = schemaDir.resolve("links.graphqls");
        Files.writeString(links, """
            extend schema @link(url: "https://specs.apollo.dev/federation/v2.10", import: ["@key"])
            extend schema @link(url: "https://specs.apollo.dev/federation/v2.9", import: ["@shareable"])
            type Query { a: String }
            """);

        Path storeDir = Files.createDirectories(tmp.resolve("store"));
        var ctx = context(tmp, storeDir, List.of(SchemaInput.file(links)));

        try (var store = GraphitronStore.captured(ctx)) {
            String recorded = store.store().dsl().select(GRAPHQL_SCHEMA_PROBLEM.MESSAGE)
                .from(GRAPHQL_SCHEMA_PROBLEM)
                .where(GRAPHQL_SCHEMA_PROBLEM.STAGE.eq("REWRITE"))
                .fetchSingle(GRAPHQL_SCHEMA_PROBLEM.MESSAGE);
            assertThatThrownBy(() -> new GraphQLRewriteGenerator(ctx, store).validate())
                .hasMessageContaining(recorded);
        }
    }

    /** A validate-only context over {@code inputs}, with a real store home so rows outlive the run. */
    private static RunContext context(Path basedir, Path storeDir, List<SchemaInput> inputs) {
        return new RunContext(
            inputs, basedir, "broken-source-fixture", basedir.resolve("out"),
            RunContext.NO_OUTPUT_PACKAGE, RunContext.NO_OUTPUT_PACKAGE, List.of())
            .withStoreDirectory(storeDir);
    }
}
