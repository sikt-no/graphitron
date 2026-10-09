package no.sikt.graphitron.model;

import no.sikt.graphitron.model.test.CapturedStore;
import no.sikt.graphitron.model.schema.input.TagLinkSynthesiser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static no.sikt.graphitron.model.Tables.GRAPHQL_SCHEMA_PROBLEM;
import static no.sikt.graphitron.model.Tables.STORE_GRAPH_SCHEMA_INPUT;
import static no.sikt.graphitron.model.Tables.STORE_SOURCE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * A tagged capture: the one fixture in the tree whose capture is configured with a
 * {@code <schemaInput tag>}, so the capture's assembly composes the corpus with the tag rewrites the
 * way the generator does, the synthesised {@code @link} importing {@code @tag} included. No other
 * capture-running fixture configures a tag and no pom in the tree sets one, so without this case a
 * tagged composition would meet the store first on a consumer's build.
 *
 * <p>Two things are pinned. The capture composed and assembled the tagged corpus cleanly: the tag
 * applications are declared by the synthesised {@code @link}, so the verdict is empty. And the stamp
 * lookup records the tagged file stamped, any generator-injected name it meets being recorded
 * unstamped rather than stamped or refused.
 */
class TaggedCaptureStampTest {

    private static final String SDL = """
        type Query { films: [Film!]! }
        type Film { title: String }
        """;

    @Test
    @DisplayName("a tagged capture composes cleanly, and stamps the tagged file rather than a sentinel")
    void aTaggedCaptureComposesCleanly(@TempDir Path tmp) {
        assertThatCode(() -> {
            try (var store = CapturedStore.tagged(tmp, SDL, "catalog")) {
                assertThat(store.dsl().select(STORE_GRAPH_SCHEMA_INPUT.TAG)
                        .from(STORE_GRAPH_SCHEMA_INPUT).fetch(STORE_GRAPH_SCHEMA_INPUT.TAG))
                    .as("the control: the capture was configured with the tag, not only the generator")
                    .containsExactly("catalog");
                assertThat(store.dsl()
                        .select(GRAPHQL_SCHEMA_PROBLEM.STAGE, GRAPHQL_SCHEMA_PROBLEM.MESSAGE)
                        .from(GRAPHQL_SCHEMA_PROBLEM).fetch())
                    .as("the tagged composition assembled: the synthesised @link declares @tag")
                    .isEmpty();

                assertThat(store.dsl().select(STORE_SOURCE.SOURCE_NAME, STORE_SOURCE.STAMP)
                        .from(STORE_SOURCE)
                        .where(STORE_SOURCE.SOURCE_KIND.eq("SCHEMA_FILE"))
                        .fetch())
                    .as("the schema file is stamped, and the generator-injected names are "
                        + "recorded unstamped rather than stamped or refused")
                    .anySatisfy(row -> {
                        assertThat(row.value1()).isEqualTo(
                            CapturedStore.fixtureFile(tmp).toAbsolutePath().normalize().toString());
                        assertThat(row.value2()).isNotNull();
                    })
                    .allSatisfy(row -> {
                        if (!row.value1().equals(CapturedStore.fixtureFile(tmp)
                            .toAbsolutePath().normalize().toString())) {
                            assertThat(row.value1()).isIn(
                                TagLinkSynthesiser.SYNTHESISED_SOURCE_NAME,
                                no.sikt.graphitron.model.schema.SchemaLoader.DIRECTIVES_SOURCE_NAME,
                                no.sikt.graphitron.model.schema.SchemaLoader.SPECIFICATION_SOURCE_NAME);
                            assertThat(row.value2()).isNull();
                        }
                    });
            }
        }).doesNotThrowAnyException();
    }
}
