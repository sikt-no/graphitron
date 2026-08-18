package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.common.configuration.TestConfiguration;
import no.sikt.graphitron.model.test.FactStores;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static no.sikt.graphitron.model.Tables.GRAPHQL_TYPE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The build-level harness itself: that a real generator run lands its facts in a store on disk, and
 * that the store outlives the run, which is the property the dev loop is built on and the reason
 * this level uses a file store where every other fixture is in memory.
 */
@PipelineTier
class BuiltStoreTest {

    private static final String SDL = """
        type Query { film: Film }

        type Film @table(name: "film") {
          title: String
        }
        """;

    @Test
    @DisplayName("a real build fills a store on disk, and a later reader finds it there")
    void aBuildLeavesItsFactsInAStoreThatOutlivesIt(@TempDir Path tmp) {
        Path home;
        try (var built = BuiltStore.run(tmp, "fixture", SDL, TestConfiguration.DEFAULT_JOOQ_PACKAGE)) {
            assertThat(built.output()).isNotNull();
            assertThat(built.schemaFile()).isEqualTo(tmp.resolve("fixture.graphqls"));
            assertThat(built.dsl().fetchCount(GRAPHQL_TYPE,
                GRAPHQL_TYPE.GRAPH_NAME.eq("fixture").and(GRAPHQL_TYPE.TYPE_NAME.eq("Film"))))
                .as("the run captured into the store it was pointed at")
                .isOne();
            home = built.storeHome();
            assertThat(Files.isDirectory(home.getParent())).isTrue();
        }

        try (var reopened = FactStores.fileBacked(home)) {
            assertThat(reopened.dsl().fetchCount(GRAPHQL_TYPE, GRAPHQL_TYPE.TYPE_NAME.eq("Film")))
                .as("a session opening the same home after the build sees what the build wrote")
                .isOne();
        }
    }
}
