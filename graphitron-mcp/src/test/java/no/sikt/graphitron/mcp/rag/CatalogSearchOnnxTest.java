package no.sikt.graphitron.mcp.rag;

import no.sikt.graphitron.mcp.StoreFixture;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Infrastructure-tier: embeds the fixture module's own captured catalog through the real bge ONNX
 * embedder and asserts the retrieval-quality payoff, the analogue of {@code BgeEmbedderOnnxTest} for the
 * catalog index. This is the only check that the descriptor composition + name normalization + hybrid
 * retrieval actually surface the right tables for a natural-language query, so it runs in CI's default
 * {@code mvn verify -Plocal-db}.
 *
 * <p>The real census rather than a corpus written to make the separation easy, which raises the bar
 * rather than lowering it: 56 tables to rank among instead of four, and the fixture DDL comments only
 * one of them, so the retrieval this asserts is mostly carried by names and their normalization. That is
 * the harder half of the claim and the one a consumer without captured comments actually gets.
 *
 * <p>Carries a plain {@code @Tag("slow")} so a developer's fast inner loop can exclude it with
 * {@code -DexcludedGroups=slow}; the tag is a local-loop convenience, not a CI skip.
 */
@Tag("slow")
class CatalogSearchOnnxTest {

    @Test
    void naturalLanguageQueriesSurfaceTheExpectedSakilaTables(@TempDir Path tmp) throws Exception {
        var cache = Files.createTempDirectory("catalog-search-onnx");
        var embedderWarm = new AsyncWarm<Embedder>("embedder", BgeEmbedder::new);
        embedderWarm.start();

        try (var fixture = StoreFixture.ofCatalog(tmp);
             var index = new CatalogSearchIndex(
                 fixture.reader(), fixture.graphName(), embedderWarm, new RagConfig(cache))) {
            assertThat(index.awaitWarm()).isInstanceOf(WarmState.Ready.class);

            // A query naming none of the table's own name tokens, only its columns'.
            assertThat(topIds(index, "postal code and district"))
                .as("the table whose columns a query names ranks first")
                .startsWith("public.address");

            // The retrieval lift the captured comments buy: this is film.description's own comment
            // text, and film is the one table the fixture DDL comments at all.
            assertThat(topIds(index, "free-text synopsis shown to renters"))
                .as("a captured column comment carries retrieval on its own")
                .startsWith("public.film");

            // Names only, inflected: nothing in the corpus spells "spoken", and language is singular.
            assertThat(topIds(index, "spoken languages"))
                .as("the normalized names retrieve without any comment to help")
                .startsWith("public.language");

            // What a whole-census corpus costs, stated rather than discovered: a query spelling another
            // table's name outranks the one it means, so the intended table is in the page rather than
            // at its head. Hybrid retrieval over 58 mostly-uncommented tables is the consumer's real
            // case, and "customer" and "stored" name two tables of their own here.
            assertThat(topIds(index, "where are customer addresses stored?"))
                .as("the address table ranks in the page for an address query")
                .contains("public.address");
        }
    }

    private static List<String> topIds(CatalogSearchIndex index, String query) {
        var outcome = index.search(query, 5);
        assertThat(outcome).isInstanceOf(CatalogSearchIndex.SearchOutcome.Hits.class);
        return ((CatalogSearchIndex.SearchOutcome.Hits) outcome).hits().stream()
            .map(EmbeddingStore.Hit::id)
            .toList();
    }

}
