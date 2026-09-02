package no.sikt.graphitron.rewrite.capture;

import no.sikt.graphitron.common.configuration.TestConfiguration;
import no.sikt.graphitron.rewrite.BuiltStore;
import no.sikt.graphitron.rewrite.ValidationError;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import static no.sikt.graphitron.model.Tables.GRAPHQL_TYPE;
import static no.sikt.graphitron.model.Tables.SQL_TABLE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

/**
 * The capture-only projection behind {@code mvn graphitron:capture}: a run that fills the store
 * and stops.
 *
 * <p>The property worth pinning is the one the command exists for. {@code validate} also fills a
 * store, but on its way to failing the build, so the only command that produced one refused to
 * produce it exactly when a reader most wants to ask what is wrong. The fixture here is a schema
 * that classifies and then fails the checks, and both halves are asserted over it: the rejection is
 * real, and the capture-only run over the same document leaves the graph in a store.
 *
 * <p>The agreement case is what keeps this a projection rather than a lesser front half. The
 * capture-only run populates the store exactly as the checking run does, so nothing the store
 * learns about a graph depends on which command was invoked; an arm that skipped a gatherer to make
 * capture cheaper fails here.
 *
 * <p>Both stores come from {@link BuiltStore}'s two arms, which build the identical context and
 * differ only in the entry point they call. That is what makes the comparison below about the
 * projections: a fixture of this test's own would differ from the checking run in whatever else it
 * happened to set up.
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

    @Test
    @DisplayName("the capture-only run populates the store as the checking run does")
    void captureWritesWhatACheckingRunWrites(@TempDir Path tmp) {
        try (var captured = BuiltStore.captured(tmp.resolve("captured"), GRAPH, REJECTED, JOOQ);
             var checked = BuiltStore.run(tmp.resolve("checked"), GRAPH, REJECTED, JOOQ)) {

            assertThat(captured.dsl().select(GRAPHQL_TYPE.TYPE_NAME, GRAPHQL_TYPE.KIND)
                .from(GRAPHQL_TYPE).orderBy(GRAPHQL_TYPE.TYPE_NAME).fetch())
                .as("the declarations the two runs transcribe are the same declarations")
                .isEqualTo(checked.dsl().select(GRAPHQL_TYPE.TYPE_NAME, GRAPHQL_TYPE.KIND)
                    .from(GRAPHQL_TYPE).orderBy(GRAPHQL_TYPE.TYPE_NAME).fetch());

            // Every base relation, not a chosen handful: a gatherer dropped to make capture-only
            // cheaper shows up as one family's count going to zero, and picking the families to
            // compare by hand is how such a drop goes unnoticed. Counts rather than contents
            // because a store carries per-run values by design (source stamps, the run's own
            // identity), so two runs' rows are equal in population and not byte for byte. The
            // derived relations follow from these, being views over them.
            var counts = rowCounts(captured.dsl());
            assertThat(counts)
                .as("a comparison over no relations would pass on any two stores")
                .hasSizeGreaterThan(100);
            assertThat(counts.values().stream().mapToInt(Integer::intValue).sum())
                .as("and so would one over relations that are all empty")
                .isPositive();
            assertThat(counts)
                .as("skipping the checks skips no gatherer: the two runs populate the same store")
                .isEqualTo(rowCounts(checked.dsl()));
        }
    }

    /** Row count per base relation, keyed by name, for the whole store. */
    private static Map<String, Integer> rowCounts(DSLContext dsl) {
        var counts = new TreeMap<String, Integer>();
        for (String relation : dsl
                .select(field(name("TABLE_NAME"), String.class))
                .from(table(name("INFORMATION_SCHEMA", "TABLES")))
                .where(field(name("TABLE_SCHEMA"), String.class).eq("PUBLIC"))
                .and(field(name("TABLE_TYPE"), String.class).eq("BASE TABLE"))
                .fetch(0, String.class)) {
            counts.put(relation, dsl.fetchCount(table(name("PUBLIC", relation))));
        }
        return counts;
    }
}
