package no.sikt.graphitron.rewrite.capture;

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

import static no.sikt.graphitron.model.Tables.GRAPHQL_SCHEMA_ERROR;
import static no.sikt.graphitron.model.Tables.GRAPHQL_SYNTAX_ERROR;
import static no.sikt.graphitron.model.Tables.GRAPHQL_TYPE;
import static no.sikt.graphitron.model.Tables.STORE_GRAPH_SOURCE;
import static no.sikt.graphitron.model.Tables.STORE_SOURCE;
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
 * runs over what survived the last one, the refusals are recorded, and the failure is pronounced
 * afterwards.
 *
 * <p>The run still fails, and with the same exception it always threw. The change is what is true of
 * the store by the time it does.
 */
@PipelineTier
class BrokenSourceStillCapturesPipelineTest {

    @Test
    @DisplayName("a run refused by the parser still captures its surviving facts and its verdicts")
    void aBrokenSourceDoesNotCostTheStoreItsOtherFacts(@TempDir Path tmp) throws IOException {
        Path schemaDir = Files.createDirectories(tmp.resolve("schema"));
        Path good = schemaDir.resolve("good.graphqls");
        Files.writeString(good, """
            type Query { film: Film }
            type Film { title: String }
            """);
        Path broken = schemaDir.resolve("broken.graphqls");
        Files.writeString(broken, """
            type Actor { name: String }
            strayTokenHere
            """);

        Path storeDir = Files.createDirectories(tmp.resolve("store"));
        var ctx = context(tmp, storeDir, List.of(SchemaInput.file(good), SchemaInput.file(broken)));

        assertThatThrownBy(() -> new GraphQLRewriteGenerator(ctx).validate())
            .as("the run still fails, and with the exception the mojo's catch arm already handles")
            .isInstanceOf(SchemaParseException.class)
            .hasMessageContaining(broken.toString());

        try (var store = GraphitronModelStore.openAt(storeDir)) {
            assertThat(store.location()).as("the fixture's store is the shared file, not a fallback")
                .isPresent();

            assertThat(store.dsl().select(GRAPHQL_SYNTAX_ERROR.SOURCE_NAME, GRAPHQL_SYNTAX_ERROR.SOURCE_LINE)
                .from(GRAPHQL_SYNTAX_ERROR).fetch()
                .map(row -> row.value1() + ":" + row.value2()))
                .as("the refused source, located, which is what an editor squiggles")
                .containsExactly(broken + ":2");

            assertThat(store.dsl().select(GRAPHQL_TYPE.TYPE_NAME).from(GRAPHQL_TYPE)
                .fetchSet(0, String.class))
                .as("the surviving file's declarations, which the old loader-throw discarded")
                .contains("Query", "Film")
                .doesNotContain("Actor");

            // The refused source is one this run read, so the store's own census owes it a row like
            // any other input. The walk cannot find it, since it declared nothing, so without this
            // the two families contradict each other: the verdict family recording that the read
            // refused a source, the census recording that the graph has no such source. The stamp
            // is what makes the currency check cover the file the author is most likely to edit
            // next, which is the broken one.
            var refusedSource = store.dsl().selectFrom(STORE_SOURCE)
                .where(STORE_SOURCE.SOURCE_NAME.eq(broken.toString()))
                .fetchOne();
            assertThat(refusedSource)
                .as("the refused source has a census row, not only a verdict row")
                .isNotNull();
            assertThat(refusedSource.getSourceKind()).isEqualTo("SCHEMA_FILE");
            assertThat(refusedSource.getStamp())
                .as("stamped like any other schema file the run read")
                .isNotNull();
            assertThat(store.dsl().select(STORE_GRAPH_SOURCE.SOURCE_NAME).from(STORE_GRAPH_SOURCE)
                .where(STORE_GRAPH_SOURCE.GRAPH_NAME.eq("broken-source-fixture"))
                .fetchSet(0, String.class))
                .as("and it is this graph's source, so a per-graph join finds it")
                .contains(broken.toString(), good.toString());
        }
    }

    @Test
    @DisplayName("a run whose schema will not assemble still captures the assembly verdict")
    void anUnassemblableSchemaRecordsItsVerdict(@TempDir Path tmp) throws IOException {
        Path schemaDir = Files.createDirectories(tmp.resolve("schema"));
        Path dangling = schemaDir.resolve("dangling.graphqls");
        // Parses, and the registry admits it; only assembly can see that Nope resolves to nothing,
        // which is the whole reason assembly runs even on a pass with no use for its output.
        Files.writeString(dangling, "type Query { gone: Nope }\n");

        Path storeDir = Files.createDirectories(tmp.resolve("store"));
        var ctx = context(tmp, storeDir, List.of(SchemaInput.file(dangling)));

        assertThatThrownBy(() -> new GraphQLRewriteGenerator(ctx).validate())
            .as("an unassemblable schema still fails the run")
            .isInstanceOf(RuntimeException.class);

        try (var store = GraphitronModelStore.openAt(storeDir)) {
            assertThat(store.dsl().select(GRAPHQL_SCHEMA_ERROR.STAGE, GRAPHQL_SCHEMA_ERROR.ERROR_CLASS)
                .from(GRAPHQL_SCHEMA_ERROR).fetch()
                .map(row -> row.value1() + "|" + row.value2()))
                .containsExactly("ASSEMBLY|MissingTypeError");
            assertThat(store.dsl().fetchCount(GRAPHQL_SYNTAX_ERROR))
                .as("nothing refused at the parse stage, so that relation stays honestly empty")
                .isZero();
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
