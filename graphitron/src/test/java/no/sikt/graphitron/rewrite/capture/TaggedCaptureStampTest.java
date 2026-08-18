package no.sikt.graphitron.rewrite.capture;

import no.sikt.graphitron.rewrite.CapturedStore;
import no.sikt.graphitron.rewrite.schema.input.TagLinkSynthesiser;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static no.sikt.graphitron.model.Tables.STORE_SOURCE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Capture's stamp lookup has two legitimate misses, and this is the one the suite would otherwise
 * ship uncovered. No other capture-running fixture in the tree configures a tag: the two tagged
 * pipeline cases stop at the attributed load and never reach capture, the untagged pipeline fixture
 * mints its one input without one, and no pom in the tree sets a {@code <schemaInput>} tag. So the
 * synthesised-{@code @link} sentinel reaches the stamp lookup only on a consumer's build, and a miss
 * set that named one sentinel would ship green here and fail at the first consumer to use tags.
 */
@PipelineTier
class TaggedCaptureStampTest {

    private static final String SDL = """
        type Query { films: [Film!]! }
        type Film { title: String }
        """;

    @Test
    @DisplayName("a tagged capture completes, and stamps the tagged file rather than the sentinel")
    void aTaggedCaptureAbsorbsTheSynthesisedLinkSentinel(@TempDir Path tmp) {
        assertThatCode(() -> {
            try (var store = CapturedStore.ofPipeline(tmp, SDL, "catalog")) {
                var registry = store.registry();
                assertThat(registry.getSchemaExtensionDefinitions())
                    .as("the tag put the synthesiser's @link extension in the registry capture walks, "
                        + "which is what makes this fixture the one that exercises the second miss")
                    .isNotEmpty();

                assertThat(store.dsl().select(STORE_SOURCE.SOURCE_NAME, STORE_SOURCE.STAMP)
                        .from(STORE_SOURCE)
                        .where(STORE_SOURCE.SOURCE_KIND.eq("SCHEMA_FILE"))
                        .fetch())
                    .as("the schema file is stamped, and the two generator-injected names are "
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
                                no.sikt.graphitron.rewrite.schema.RewriteSchemaLoader.DIRECTIVES_SOURCE_NAME);
                            assertThat(row.value2()).isNull();
                        }
                    });
            }
        }).doesNotThrowAnyException();
    }
}
