package no.sikt.graphitron.mcp.rag;

import no.sikt.graphitron.rewrite.catalog.CatalogFacts;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Infrastructure-tier (R386): embeds a small Sakila-shaped {@link CatalogFacts} through the real bge
 * ONNX embedder and asserts the retrieval-quality payoff, the analogue of R372's
 * {@code BgeEmbedderOnnxTest} for the catalog index. This is the only check that the descriptor
 * composition + name normalization + hybrid retrieval actually surface the right tables for a
 * natural-language query, so it runs in CI's default {@code mvn verify -Plocal-db}.
 *
 * <p>Carries a plain {@code @Tag("slow")} so a developer's fast inner loop can exclude it with
 * {@code -DexcludedGroups=slow}; the tag is a local-loop convenience, not a CI skip.
 */
@Tag("slow")
class CatalogSearchOnnxTest {

    @Test
    void naturalLanguageQueriesSurfaceTheExpectedSakilaTables() throws Exception {
        var cache = Files.createTempDirectory("catalog-search-onnx");
        var embedderWarm = new AsyncWarm<Embedder>("embedder", BgeEmbedder::new);
        embedderWarm.start();
        var facts = sakilaFacts();

        try (var index = new CatalogSearchIndex(() -> facts, embedderWarm, new RagConfig(cache))) {
            assertThat(index.awaitWarm()).isInstanceOf(WarmState.Ready.class);

            assertThat(topIds(index, "where are customer addresses stored?"))
                .as("the address table ranks for an address query without the word 'address' being its only token")
                .contains("public.address");
            assertThat(topIds(index, "movie rental payments"))
                .as("the payment table ranks for a payments query")
                .contains("public.payment");
        }
    }

    private static List<String> topIds(CatalogSearchIndex index, String query) {
        var outcome = index.search(query, 3);
        assertThat(outcome).isInstanceOf(CatalogSearchIndex.SearchOutcome.Hits.class);
        return ((CatalogSearchIndex.SearchOutcome.Hits) outcome).hits().stream()
            .map(EmbeddingStore.Hit::id)
            .toList();
    }

    /** A handful of Sakila tables with comments, enough to make the semantic separation meaningful. */
    private static CatalogFacts sakilaFacts() {
        var map = new LinkedHashMap<String, CatalogFacts.Table>();
        put(map, table("address", "Postal addresses for customers, staff, and stores",
            col("address_id", "Surrogate key"),
            col("address", "Street address line"),
            col("district", "State or province"),
            col("postal_code", "ZIP or postal code")));
        put(map, table("payment", "Each payment a customer made for a film rental",
            col("payment_id", "Surrogate key"),
            col("customer_id", "Customer who paid"),
            col("amount", "Amount paid"),
            col("payment_date", "When the payment was made")));
        put(map, table("film", "Catalog of films available for rental",
            col("film_id", "Surrogate key"),
            col("title", "Film title"),
            col("description", "Short film synopsis")));
        put(map, table("language", "Languages a film can be in",
            col("language_id", "Surrogate key"),
            col("name", "Language name")));
        return new CatalogFacts(map);
    }

    private static void put(LinkedHashMap<String, CatalogFacts.Table> map, CatalogFacts.Table table) {
        map.put(table.qualifiedName(), table);
    }

    private static CatalogFacts.Table table(String name, String comment, CatalogFacts.Column... columns) {
        return new CatalogFacts.Table(
            "public", name, Optional.of(comment), List.of(columns),
            Optional.empty(), List.of(), List.of(), CatalogFacts.ForeignKeys.empty());
    }

    private static CatalogFacts.Column col(String sqlName, String comment) {
        return new CatalogFacts.Column(sqlName, sqlName.toUpperCase(), "varchar", false, Optional.of(comment));
    }
}
