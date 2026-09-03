package no.sikt.graphitron.rewrite.derive;

import no.sikt.graphitron.model.jooq.JooqCatalog;
import no.sikt.graphitron.model.test.CapturedStore;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static no.sikt.graphitron.common.configuration.TestConfiguration.testContext;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_NODE;
import static no.sikt.graphitron.model.Tables.INTENT_NODE_TYPE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * That the gatherer writes {@code graphitron_node} at all, over SDL an author could have
 * written. Its rules are pinned row by row in {@code NodesTest} against a seeded store; what
 * that test cannot reach is the wiring, because it calls the producer itself. A stage ordered
 * before the {@code @node} decode has flushed writes an empty relation and breaks nothing that
 * runs today, no reader having moved onto it yet, so the ordering needs a witness of its own
 * before it has one by accident.
 *
 * <p>The second case is the correctness claim over real SDL rather than seeded rows: a
 * {@code @node} the parser accepted, on a type with no {@code @table}, is not a node. Both sides
 * are asserted, so a fixture that stopped producing the shape fails rather than passes.
 */
@PipelineTier
class NodeCaptureTest {

    private static final String GRAPH = CapturedStore.GRAPH;

    @TempDir
    Path tmp;

    private static final String SDL = """
        type Film @node @table(name: "film") { title: String }
        type Summary @node { count: Int }
        type Query { films: [Film!]!, summary: Summary }
        """;

    @Test
    @DisplayName("a @node over a @table is a node once the gatherer has run")
    void aCapturedNodeOverATableIsANode() {
        try (var store = CapturedStore.ofCatalog(tmp, SDL, jooq())) {
            assertThat(nodes(store.dsl()))
                .as("the decode and the binding are both in hand where the stage runs")
                .contains("Film");
        }
    }

    @Test
    @DisplayName("a @node on a type with no @table is not a node, whatever the parser accepted")
    void aCapturedNodeWithNoTableIsNotANode() {
        try (var store = CapturedStore.ofCatalog(tmp, SDL, jooq())) {
            assertThat(membership(store.dsl()))
                .as("the membership view takes the directive at its word, which is what makes this"
                    + " a difference rather than a fixture that wrote no @node")
                .contains("Summary");
            assertThat(nodes(store.dsl()))
                .as("@node only takes effect over @table, and this relation states what took effect")
                .doesNotContain("Summary");
        }
    }

    private static List<String> nodes(DSLContext dsl) {
        return dsl.select(GRAPHITRON_NODE.TYPE_NAME).from(GRAPHITRON_NODE)
            .where(GRAPHITRON_NODE.GRAPH_NAME.eq(GRAPH))
            .fetch(GRAPHITRON_NODE.TYPE_NAME);
    }

    private static List<String> membership(DSLContext dsl) {
        return dsl.select(INTENT_NODE_TYPE.TYPE_NAME).from(INTENT_NODE_TYPE)
            .where(INTENT_NODE_TYPE.GRAPH_NAME.eq(GRAPH))
            .fetch(INTENT_NODE_TYPE.TYPE_NAME);
    }

    private static JooqCatalog jooq() {
        var ctx = testContext();
        return new JooqCatalog(ctx.jooqPackage(), ctx.codegenLoader());
    }
}
