package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.common.configuration.TestConfiguration;
import no.sikt.graphitron.rewrite.schema.input.SchemaSource;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static no.sikt.graphitron.model.Tables.GRAPHQL_SYNTAX_ERROR;
import static no.sikt.graphitron.model.Tables.GRAPHQL_TYPE;
import static no.sikt.graphitron.model.Tables.SQL_TABLE;
import static no.sikt.graphitron.model.Tables.STORE_GRAPH;
import static no.sikt.graphitron.model.Tables.STORE_GRAPH_SOURCE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The capture-level harness itself, over the arms whose whole point is a shape no single-fixture
 * caller exercises: a second graph in one store, two graphs over one document, a warm round standing
 * the previous one down, and a read that refused one of its sources. Each is a claim about what
 * capture does rather than about the helper, which is why they are worth pinning here rather than left
 * to the first consumer to discover.
 */
@UnitTier
class CapturedStoreTest {

    private static final String SDL = """
        type Query { film: Film }

        type Film {
          title: String
        }
        """;

    @Test
    @DisplayName("a fixture's file is named for its graph, so two graphs in one directory keep their own")
    void theFixtureFileIsKeyedOnTheGraphName(@TempDir Path tmp) throws IOException {
        try (var store = CapturedStore.of(tmp, "alpha", SDL)) {
            store.andGraph("beta", "type Query { ping: String }");

            assertThat(store.file()).isEqualTo(tmp.resolve("alpha.graphqls"));
            assertThat(Files.exists(tmp.resolve("beta.graphqls"))).isTrue();
            assertThat(Files.readString(tmp.resolve("alpha.graphqls")))
                .as("the second capture wrote beside the first rather than over it")
                .isEqualTo(SDL);

            assertThat(store.dsl().fetch(STORE_GRAPH).map(r -> r.get(STORE_GRAPH.GRAPH_NAME)))
                .containsExactlyInAnyOrder("alpha", "beta");
            assertThat(store.dsl().fetchCount(GRAPHQL_TYPE,
                GRAPHQL_TYPE.GRAPH_NAME.eq("beta").and(GRAPHQL_TYPE.TYPE_NAME.eq("Film"))))
                .as("one graph's types do not leak into the other's partition")
                .isZero();
        }
    }

    @Test
    @DisplayName("two graphs over one document are two memberships of the same source")
    void aSharedFixtureFileIsOneSourceWithTwoMemberships(@TempDir Path tmp) {
        try (var store = CapturedStore.of(tmp, "alpha", SDL)) {
            store.andGraphSharingTheFile("beta");

            assertThat(store.dsl()
                .select(STORE_GRAPH_SOURCE.GRAPH_NAME)
                .from(STORE_GRAPH_SOURCE)
                .where(STORE_GRAPH_SOURCE.SOURCE_NAME.eq(SchemaSource.file(store.file()).sourceName()))
                .fetch(STORE_GRAPH_SOURCE.GRAPH_NAME))
                .as("one document, two memberships, both true")
                .containsExactlyInAnyOrder("alpha", "beta");
            assertThat(Files.exists(tmp.resolve("beta.graphqls")))
                .as("nothing was written for the second graph; it took the first one's file")
                .isFalse();
        }
    }

    @Test
    @DisplayName("a warm round replaces this graph's rows rather than accumulating beside them")
    void aWarmRecaptureStandsThePreviousRoundDown(@TempDir Path tmp) {
        try (var store = CapturedStore.of(tmp, SDL)) {
            assertThat(store.dsl().fetchCount(GRAPHQL_TYPE, GRAPHQL_TYPE.TYPE_NAME.eq("Film"))).isOne();

            store.recapture("type Query { actor: Actor }\n\ntype Actor { name: String }\n");

            assertThat(store.dsl().fetch(GRAPHQL_TYPE, GRAPHQL_TYPE.TYPE_NAME.in("Film", "Actor"))
                .map(r -> r.get(GRAPHQL_TYPE.TYPE_NAME)))
                .as("the round the consumer just ran is what the graph holds")
                .containsExactly("Actor");
        }
    }

    @Test
    @DisplayName("the catalog arm captures the generated model; the bare arm has no catalog to capture")
    void theCatalogArmIsWhatPutsTablesInTheStore(@TempDir Path tmp) {
        try (var store = CapturedStore.ofCatalog(tmp.resolve("catalog"), SDL,
                new JooqCatalog(TestConfiguration.DEFAULT_JOOQ_PACKAGE))) {
            assertThat(store.dsl().fetchCount(SQL_TABLE)).isPositive();
        }
        try (var store = CapturedStore.of(tmp.resolve("bare"), SDL)) {
            assertThat(store.dsl().fetchCount(SQL_TABLE)).isZero();
        }
    }

    @Test
    @DisplayName("the refused arm keeps the surviving source's rows and leaves the verdict beside them")
    void theRefusedArmCapturesBothHalvesOfARefusedRead(@TempDir Path tmp) {
        try (var store = CapturedStore.ofRefusedSchema(tmp, SDL, "type Actor { name: String\n",
                new JooqCatalog(TestConfiguration.DEFAULT_JOOQ_PACKAGE))) {
            assertThat(store.dsl().fetchCount(GRAPHQL_TYPE, GRAPHQL_TYPE.TYPE_NAME.eq("Film")))
                .as("the source that parsed is in the store, which is what a reader answers from")
                .isOne();
            assertThat(store.dsl().fetchCount(GRAPHQL_SYNTAX_ERROR))
                .as("and the refusal is recorded, which is what makes the read not-clean")
                .isPositive();
        }
    }

    @Test
    @DisplayName("the refused arm fails on a second source nothing objects to")
    void theRefusedArmWillNotStandInForACleanRead(@TempDir Path tmp) {
        // Left to itself the capture would succeed and the fixture would go on being read as a
        // refusal, so the arm says so rather than handing back a store that disagrees with its name.
        assertThatThrownBy(() -> CapturedStore.ofRefusedSchema(tmp, SDL, "type Actor { name: String }\n",
            new JooqCatalog(TestConfiguration.DEFAULT_JOOQ_PACKAGE)))
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("fixture-refused.graphqls");
    }

    @Test
    @DisplayName("the closure form hands over the captured store and closes it after")
    void theClosureFormIsTheHandleWithItsLifetimeTakenCareOf(@TempDir Path tmp) {
        CapturedStore.withCapturedStore(tmp, SDL, dsl ->
            assertThat(dsl.fetchCount(GRAPHQL_TYPE, GRAPHQL_TYPE.TYPE_NAME.eq("Film"))).isOne());
    }
}
