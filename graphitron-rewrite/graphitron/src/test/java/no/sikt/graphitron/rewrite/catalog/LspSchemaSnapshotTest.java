package no.sikt.graphitron.rewrite.catalog;

import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Invariant pinning for {@link LspSchemaSnapshot}. Sealed-switch
 * exhaustiveness is enforced by {@code javac}, not by tests; the cases
 * here cover the directive lookup default method and the unmodifiable
 * defensive copy.
 */
@UnitTier
class LspSchemaSnapshotTest {

    @Test
    void unavailableFactoryReturnsUnavailable() {
        assertThat(LspSchemaSnapshot.unavailable())
            .isInstanceOf(LspSchemaSnapshot.Unavailable.class);
    }

    @Test
    void builtCurrentDirectiveLookupIsCaseSensitive() {
        var shape = new DirectiveShape("key", List.of(), Optional.empty());
        var snapshot = new LspSchemaSnapshot.Built.Current(List.of(shape));

        assertThat(snapshot.directive("key")).contains(shape);
        assertThat(snapshot.directive("Key")).isEmpty();
        assertThat(snapshot.directive("KEY")).isEmpty();
    }

    @Test
    void builtPreviousDirectiveLookupBehavesIdentically() {
        var shape = new DirectiveShape("key", List.of(), Optional.empty());
        var snapshot = new LspSchemaSnapshot.Built.Previous(List.of(shape));

        assertThat(snapshot.directive("key")).contains(shape);
        assertThat(snapshot.directive("Key")).isEmpty();
    }

    @Test
    void builtDirectivesAreUnmodifiable() {
        var shape = new DirectiveShape("key", List.of(), Optional.empty());
        var mutable = new java.util.ArrayList<>(List.of(shape));
        var snapshot = new LspSchemaSnapshot.Built.Current(mutable);

        assertThatThrownBy(() -> snapshot.directives().add(
            new DirectiveShape("other", List.of(), Optional.empty())))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void builtCurrentDefensivelyCopiesItsDirectivesList() {
        var shape = new DirectiveShape("key", List.of(), Optional.empty());
        var mutable = new java.util.ArrayList<>(List.of(shape));
        var snapshot = new LspSchemaSnapshot.Built.Current(mutable);

        mutable.clear();

        // Defensive copy at construction means the post-construction
        // clear() does not bleed into the snapshot.
        assertThat(snapshot.directives()).containsExactly(shape);
    }
}
