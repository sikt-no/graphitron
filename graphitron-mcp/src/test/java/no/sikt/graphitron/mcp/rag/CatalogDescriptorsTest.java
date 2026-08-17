package no.sikt.graphitron.mcp.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit-tier: the descriptor composer and the name normalizer are pure and ONNX-free, so the
 * retrieval-lift invariants pin here without loading a model. Covers snake_case /
 * camelCase / acronym / digit / single-word splitting, the comment-present vs name-only degradation,
 * and that every descriptor carries both the raw SQL token and its normalized words.
 *
 * <p>Store-free deliberately, and the property split is what licenses it: formatting and identifier
 * normalization are the composer's own business and need no rows. That the rows a real capture yields
 * compose the string the index embeds is a different claim, and
 * {@code CatalogSearchCorpusTest} owns it over a captured store.
 */
class CatalogDescriptorsTest {

    @Test
    void splitWordsNormalizesSnakeCamelAcronymDigitAndSingleWord() {
        assertThat(CatalogDescriptors.splitWords("film_actor")).isEqualTo("film actor");
        assertThat(CatalogDescriptors.splitWords("lastUpdate")).isEqualTo("last update");
        assertThat(CatalogDescriptors.splitWords("customerID")).isEqualTo("customer id");
        assertThat(CatalogDescriptors.splitWords("IDColumn")).isEqualTo("id column");
        assertThat(CatalogDescriptors.splitWords("address2")).isEqualTo("address 2");
        assertThat(CatalogDescriptors.splitWords("film")).isEqualTo("film");
        assertThat(CatalogDescriptors.splitWords("FILM")).isEqualTo("film");
        assertThat(CatalogDescriptors.splitWords("")).isEmpty();
    }

    @Test
    void descriptorCarriesRawAndNormalizedTokensAndTheTableComment() {
        var table = new CorpusTable(
            "public", "film_actor", "join table linking films to actors",
            List.of(
                new CorpusTable.Column("actor_id", "the actor"),
                new CorpusTable.Column("last_update", null)));

        String descriptor = CatalogDescriptors.descriptor(table);

        // Raw SQL token (so BM25 still matches film_actor exactly) and the normalized words side by side.
        assertThat(descriptor).contains("Table film_actor (film actor)");
        // Table comment surfaces on its own line.
        assertThat(descriptor).contains("Comment: join table linking films to actors");
        // Each column carries raw + normalized; a column comment appears, an absent one does not.
        assertThat(descriptor).contains("actor_id (actor id): the actor");
        assertThat(descriptor).contains("last_update (last update)");
        assertThat(descriptor).doesNotContain("last_update (last update):");
    }

    @Test
    void descriptorDegradesToNamesOnlyWhenNoCommentsWereCaptured() {
        var table = new CorpusTable(
            "public", "address", null,
            List.of(new CorpusTable.Column("address_id", null)));

        String descriptor = CatalogDescriptors.descriptor(table);

        // No comment line at all; still useful via the normalized words.
        assertThat(descriptor).doesNotContain("Comment:");
        assertThat(descriptor).contains("Table address (address)");
        assertThat(descriptor).contains("address_id (address id)");
    }

    @Test
    void corpusHashCoversTheExactDescriptorStringsAndIsStableUnderRecomposition() {
        var descriptors = List.of("Table film (film)", "Table actor (actor)");

        // Recomposing the identical strings yields the identical hash.
        assertThat(CatalogDescriptors.corpusHash(descriptors))
            .isEqualTo(CatalogDescriptors.corpusHash(List.of("Table film (film)", "Table actor (actor)")));
        // A changed descriptor (a renamed column, an added comment) changes the hash.
        assertThat(CatalogDescriptors.corpusHash(descriptors))
            .isNotEqualTo(CatalogDescriptors.corpusHash(List.of("Table film (film)", "Table actor (actor) changed")));
        // Order is part of the identity; the boundary between descriptors cannot be re-segmented away.
        assertThat(CatalogDescriptors.corpusHash(List.of("ab", "c")))
            .isNotEqualTo(CatalogDescriptors.corpusHash(List.of("a", "bc")));
    }
}
