package no.sikt.graphitron.mcp;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Coverage pin over the aggregate's dimension vocabulary, in the {@code VariantCoverageTest}
 * mould: every {@link DiagnosticFacets.Dimension} is declared, deliberately and visibly, in
 * exactly one of the two companion buckets (typed-key / location-derived), which is what makes
 * the wire contract's "these clusters are typed" claim a live partition instead of prose. The
 * bucket set is two, not three, because {@code messageTemplate} is deliberately not a
 * dimension: with it omitted there is no prose-derived bucket, and a future dimension grouping
 * on rendered text has to reopen that decision here, visibly. The partition is also the
 * documentation's structure: the tool description's dimension gloss renders from these bucket
 * lists, so the second assertion pins that the render names every dimension rather than
 * trusting hand-maintained prose.
 *
 * <p>The second assertion covers {@link DiagnosticFacets.Filter} on the same terms. A filter is
 * not a bucket of the dimension partition (it cannot be grouped by at all, which is why it sits
 * outside the enum), but it shares the {@code where} map's key namespace and the same gloss, so
 * a collision or an undiscoverable key fails here.
 */
class DiagnosticDimensionCoverageTest {

    @Test
    void everyDimensionIsDeclaredInExactlyOneBucket() {
        var typed = new HashSet<>(DiagnosticFacets.TYPED_KEY_DIMENSIONS);
        var location = new HashSet<>(DiagnosticFacets.LOCATION_DERIVED_DIMENSIONS);

        var overlap = typed.stream().filter(location::contains)
            .map(Enum::name).sorted().toList();
        assertThat(overlap)
            .as("a dimension cannot be both typed-key and location-derived")
            .isEmpty();

        var union = new HashSet<>(typed);
        union.addAll(location);
        assertThat(union)
            .as("every dimension must be declared in exactly one bucket; a new dimension edits "
                + "this partition on the commit that adds it")
            .containsExactlyInAnyOrderElementsOf(EnumSet.allOf(DiagnosticFacets.Dimension.class));

        // Duplicates inside one ordered bucket list would double-render the gloss.
        assertThat(DiagnosticFacets.TYPED_KEY_DIMENSIONS).doesNotHaveDuplicates();
        assertThat(DiagnosticFacets.LOCATION_DERIVED_DIMENSIONS).doesNotHaveDuplicates();
    }

    @Test
    void wireNamesAreUniqueAndTheRenderedGlossNamesEveryDimension() {
        var names = DiagnosticFacets.Dimension.wireNames();
        assertThat(names).doesNotHaveDuplicates();
        assertThat(Set.copyOf(names)).hasSize(DiagnosticFacets.Dimension.values().length);

        String gloss = DiagnosticFacets.dimensionGloss();
        for (String name : names) {
            assertThat(gloss)
                .as("the tool description's dimension gloss renders from the partition, so it "
                    + "names every dimension")
                .contains(name);
        }

        // The where-only filters share one namespace with the dimensions, since one `where` map
        // is keyed by both, and the gloss renders them too: a key an agent can pass and cannot
        // discover is a guess-and-retry the closed vocabulary exists to remove.
        var filters = DiagnosticFacets.Filter.wireNames();
        assertThat(filters).doesNotHaveDuplicates();
        assertThat(filters)
            .as("a filter's wire name cannot collide with a dimension's; one where map is keyed "
                + "by both, and groupBy takes the dimensions alone")
            .doesNotContainAnyElementsOf(names);
        for (String name : filters) {
            assertThat(gloss).as("the gloss names every where-only filter").contains(name);
        }
    }
}
