package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.model.Rejection;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that {@link RejectionKind#of(Rejection)} is total over the sealed permits: every
 * {@link Rejection} sub-arm projects to exactly one {@link RejectionKind} value, and the three
 * top-level arms cover the full range of the enum.
 */
@UnitTier
class RejectionKindProjectionTest {

    @Test
    void authorErrorStructuralProjectsToAuthorError() {
        assertThat(RejectionKind.of(Rejection.structural("x"))).isEqualTo(RejectionKind.AUTHOR_ERROR);
    }

    @Test
    void authorErrorUnknownNameProjectsToAuthorError() {
        var r = Rejection.unknownColumn("x", "x", List.of());
        assertThat(RejectionKind.of(r)).isEqualTo(RejectionKind.AUTHOR_ERROR);
    }

    @Test
    void invalidSchemaStructuralProjectsToInvalidSchema() {
        assertThat(RejectionKind.of(Rejection.invalidSchema("x"))).isEqualTo(RejectionKind.INVALID_SCHEMA);
    }

    @Test
    void invalidSchemaDirectiveConflictProjectsToInvalidSchema() {
        var r = Rejection.directiveConflict(List.of("a", "b"), "x");
        assertThat(RejectionKind.of(r)).isEqualTo(RejectionKind.INVALID_SCHEMA);
    }

    @Test
    void deferredProjectsToDeferred() {
        var r = Rejection.deferred("x");
        assertThat(RejectionKind.of(r)).isEqualTo(RejectionKind.DEFERRED);
    }

    @Test
    void deferredAtProjectsToDeferred() {
        var r = Rejection.deferredAt("x", "slug");
        assertThat(RejectionKind.of(r)).isEqualTo(RejectionKind.DEFERRED);
    }

    @Test
    void displayNameRendersKebabCase() {
        assertThat(RejectionKind.AUTHOR_ERROR.displayName()).isEqualTo("author-error");
        assertThat(RejectionKind.INVALID_SCHEMA.displayName()).isEqualTo("invalid-schema");
        assertThat(RejectionKind.DEFERRED.displayName()).isEqualTo("deferred");
    }
}
