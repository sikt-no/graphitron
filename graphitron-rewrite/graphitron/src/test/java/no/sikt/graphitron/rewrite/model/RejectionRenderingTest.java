package no.sikt.graphitron.rewrite.model;

import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Captures the {@code message()} contract on every {@link Rejection} sealed leaf, so the lift
 * onto a typed hierarchy doesn't drift from the byte-stable log surface validator consumers
 * depend on. Mirrors trunk's prose at the rendering boundary.
 */
@UnitTier
class RejectionRenderingTest {

    @Test
    void authorErrorStructuralReturnsReasonVerbatim() {
        var r = Rejection.structural("@reference path is required");
        assertThat(r.message()).isEqualTo("@reference path is required");
    }

    @Test
    void authorErrorUnknownNameWithCandidatesAppendsHint() {
        var r = Rejection.unknownColumn(
            "column 'tle' could not be resolved on table 'film'",
            "tle",
            List.of("title", "rental_rate", "release_year"));
        assertThat(r.message()).isEqualTo(
            "column 'tle' could not be resolved on table 'film'; did you mean: title, rental_rate, release_year");
    }

    @Test
    void authorErrorUnknownNameWithEmptyCandidatesOmitsHint() {
        var r = Rejection.unknownColumn(
            "column 'foo' could not be resolved on table 'bar'",
            "foo",
            List.of());
        assertThat(r.message()).isEqualTo("column 'foo' could not be resolved on table 'bar'");
    }

    @Test
    void invalidSchemaStructuralReturnsReasonVerbatim() {
        var r = Rejection.invalidSchema("@asConnection on inline (non-@splitQuery) TableField is not supported");
        assertThat(r.message()).isEqualTo("@asConnection on inline (non-@splitQuery) TableField is not supported");
    }

    @Test
    void invalidSchemaDirectiveConflictReturnsReasonVerbatim() {
        var r = Rejection.directiveConflict(
            List.of("service", "mutation"),
            "@service, @mutation are mutually exclusive");
        assertThat(r.message()).isEqualTo("@service, @mutation are mutually exclusive");
    }

    @Test
    void deferredWithoutSlugReturnsSummaryVerbatim() {
        var r = Rejection.deferred("fields on 'Subscription' (Subscription is not supported)");
        assertThat(r.message()).isEqualTo("fields on 'Subscription' (Subscription is not supported)");
    }

    @Test
    void deferredWithSlugAppendsRoadmapPath() {
        var r = Rejection.deferredAt(
            "@service on a @record-typed parent is not yet supported",
            "service-record-field");
        assertThat(r.message()).isEqualTo(
            "@service on a @record-typed parent is not yet supported — see graphitron-rewrite/roadmap/service-record-field.md");
    }

    @Test
    void deferredKeyedByVariantClassRendersWithoutSlugSuffix() {
        var r = Rejection.deferred("Single-cardinality requires single-hop", ChildField.SplitTableField.class);
        assertThat(r.message()).isEqualTo("Single-cardinality requires single-hop");
    }

    @Test
    void unknownNameCarriesTypedCandidatesList() {
        var r = (Rejection.AuthorError.UnknownName) Rejection.unknownColumn(
            "column 'x' could not be resolved", "x", List.of("a", "b", "c"));
        assertThat(r.candidates()).containsExactly("a", "b", "c");
        assertThat(r.attempt()).isEqualTo("x");
        assertThat(r.attemptKind()).isEqualTo(Rejection.AttemptKind.COLUMN);
    }

    @Test
    void directiveConflictCarriesTypedDirectivesList() {
        var r = (Rejection.InvalidSchema.DirectiveConflict) Rejection.directiveConflict(
            List.of("service", "mutation"), "...");
        assertThat(r.directives()).containsExactly("service", "mutation");
    }
}
