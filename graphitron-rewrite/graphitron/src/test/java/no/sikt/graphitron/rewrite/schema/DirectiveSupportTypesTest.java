package no.sikt.graphitron.rewrite.schema;

import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the support-type set derived from {@code directives.graphqls} (R291). The set is
 * derived, not hand-maintained; this test exists so an edit to {@code directives.graphqls}
 * that adds or removes a type definition changes the published/strictly-internal split
 * consciously rather than silently.
 */
@UnitTier
class DirectiveSupportTypesTest {

    @Test
    void derivedSetPinsTheDeclaredTypeNames() {
        assertThat(DirectiveSupportTypes.all()).containsExactlyInAnyOrder(
            "ErrorHandler",
            "ReferencesForType",
            "FieldSort",
            "ExternalCodeReference",
            "ReferenceElement",
            "ErrorHandlerType",
            "SortDirection",
            "MutationType");
    }

    @Test
    void publishedTierIsExactlySortDirection() {
        assertThat(DirectiveSupportTypes.published()).containsExactly("SortDirection");
    }

    @Test
    void strictlyInternalIsEverythingElse() {
        assertThat(DirectiveSupportTypes.strictlyInternal())
            .containsExactlyInAnyOrderElementsOf(
                DirectiveSupportTypes.all().stream()
                    .filter(name -> !DirectiveSupportTypes.published().contains(name))
                    .toList());
        assertThat(DirectiveSupportTypes.isStrictlyInternal("SortDirection")).isFalse();
        assertThat(DirectiveSupportTypes.isStrictlyInternal("ErrorHandler")).isTrue();
        assertThat(DirectiveSupportTypes.isSupportType("Film")).isFalse();
    }
}
