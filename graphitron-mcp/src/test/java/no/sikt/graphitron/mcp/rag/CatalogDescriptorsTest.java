package no.sikt.graphitron.mcp.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit-tier: the two functions whose input is a string rather than a row. {@code splitWords} takes a
 * SQL identifier and {@code corpusHash} takes descriptors already composed, so both pin here with no
 * store and no model: snake_case / camelCase / acronym / digit / single-word splitting, and that the
 * hash covers the exact strings handed to the embedder, in order, unsegmentable.
 *
 * <p>What the composer does with rows belongs where the rows come from. {@link CatalogCorpusTest} owns
 * the descriptor's own arms (both comment grains, the names-only degradation, the raw token beside its
 * normalized words) over a captured census, because a hand-built row can spell a comment or an ordinal
 * the capture never spells.
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
