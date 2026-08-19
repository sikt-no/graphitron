package no.sikt.graphitron.rewrite.catalog;

import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Invariant pinning for {@link LspSchemaSnapshot}. Sealed-switch exhaustiveness is enforced by
 * {@code javac}, not by tests; the cases here cover the classification lookups and the unmodifiable
 * defensive copy the built permit makes at construction.
 */
@UnitTier
class LspSchemaSnapshotTest {

    private static final FieldClassification CLASSIFICATION =
        new FieldClassification.ServiceBacked("com.example.Service", "find", false, null, null);

    @Test
    void unavailableFactoryReturnsUnavailable() {
        assertThat(LspSchemaSnapshot.unavailable())
            .isInstanceOf(LspSchemaSnapshot.Unavailable.class);
    }

    @Test
    void builtFieldLookupIsCaseSensitive() {
        var snapshot = new LspSchemaSnapshot.Built(
            Map.of("Query.film", CLASSIFICATION), Map.of());

        assertThat(snapshot.fieldClassification("Query", "film")).contains(CLASSIFICATION);
        assertThat(snapshot.fieldClassification("query", "film")).isEmpty();
        assertThat(snapshot.fieldClassification("Query", "Film")).isEmpty();
    }

    @Test
    void builtClassificationsAreUnmodifiable() {
        var snapshot = new LspSchemaSnapshot.Built(
            Map.of("Query.film", CLASSIFICATION), Map.of());

        assertThatThrownBy(() ->
            snapshot.fieldClassificationsByCoord().put("Query.other", CLASSIFICATION))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void builtDefensivelyCopiesItsClassificationMaps() {
        var mutable = new LinkedHashMap<String, FieldClassification>();
        mutable.put("Query.film", CLASSIFICATION);
        var snapshot = new LspSchemaSnapshot.Built(mutable, Map.of());

        mutable.clear();

        // Defensive copy at construction means the post-construction clear() does not bleed into
        // the snapshot.
        assertThat(snapshot.fieldClassificationsByCoord()).containsKey("Query.film");
    }
}
