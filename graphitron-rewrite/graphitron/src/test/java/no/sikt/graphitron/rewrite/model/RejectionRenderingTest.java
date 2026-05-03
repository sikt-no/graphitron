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

    @Test
    void unknownTypeNameFactoryTagsAttemptKindTypeName() {
        var r = (Rejection.AuthorError.UnknownName) Rejection.unknownTypeName(
            "@nodeId(typeName:) type 'Studieprogam' does not exist in the schema",
            "Studieprogam", List.of("Studieprogram", "Student"));
        assertThat(r.attemptKind()).isEqualTo(Rejection.AttemptKind.TYPE_NAME);
        assertThat(r.attempt()).isEqualTo("Studieprogam");
        assertThat(r.candidates()).containsExactly("Studieprogram", "Student");
    }

    @Test
    void unknownEnumConstantFactoryTagsAttemptKindEnumConstant() {
        var r = (Rejection.AuthorError.UnknownName) Rejection.unknownEnumConstant(
            "constant 'GREEM' not found", "GREEM", List.of("GREEN", "RED"));
        assertThat(r.attemptKind()).isEqualTo(Rejection.AttemptKind.ENUM_CONSTANT);
    }

    @Test
    void unknownNodeIdKeyColumnFactoryTagsAttemptKindNodeIdKeyColumn() {
        var r = (Rejection.AuthorError.UnknownName) Rejection.unknownNodeIdKeyColumn(
            "key column 'id_3' could not be resolved", "id_3", List.of("id_1", "id_2"));
        assertThat(r.attemptKind()).isEqualTo(Rejection.AttemptKind.NODEID_KEY_COLUMN);
    }

    @Test
    void unknownDmlKindFactoryTagsAttemptKindDmlKind() {
        var r = (Rejection.AuthorError.UnknownName) Rejection.unknownDmlKind(
            "unknown @mutation(typeName:) value 'BOGUS'",
            "BOGUS", List.of("INSERT", "UPDATE", "DELETE"));
        assertThat(r.attemptKind()).isEqualTo(Rejection.AttemptKind.DML_KIND);
        assertThat(r.candidates()).containsExactly("INSERT", "UPDATE", "DELETE");
    }

    @Test
    void prefixedWithPreservesUnknownNameTypedFields() {
        var inner = (Rejection.AuthorError.UnknownName) Rejection.unknownServiceMethod(
            "method 'getActor' not found in class 'A'", "getActor", List.of("getActors", "getFilms"));
        var prefixed = (Rejection.AuthorError.UnknownName) inner.prefixedWith("service method could not be resolved — ");
        assertThat(prefixed.attempt()).isEqualTo("getActor");
        assertThat(prefixed.candidates()).containsExactly("getActors", "getFilms");
        assertThat(prefixed.attemptKind()).isEqualTo(Rejection.AttemptKind.SERVICE_METHOD);
        assertThat(prefixed.message()).startsWith("service method could not be resolved — method 'getActor' not found");
    }

    @Test
    void prefixedWithPreservesAuthorErrorStructural() {
        var inner = (Rejection.AuthorError.Structural) Rejection.structural("inner reason");
        var prefixed = (Rejection.AuthorError.Structural) inner.prefixedWith("ctx: ");
        assertThat(prefixed.reason()).isEqualTo("ctx: inner reason");
    }

    @Test
    void prefixedWithPreservesDirectiveConflictDirectives() {
        var inner = (Rejection.InvalidSchema.DirectiveConflict) Rejection.directiveConflict(
            List.of("service", "mutation"), "@service, @mutation are mutually exclusive");
        var prefixed = (Rejection.InvalidSchema.DirectiveConflict) inner.prefixedWith("on field 'X.y': ");
        assertThat(prefixed.directives()).containsExactly("service", "mutation");
        assertThat(prefixed.reason()).isEqualTo("on field 'X.y': @service, @mutation are mutually exclusive");
    }

    @Test
    void prefixedWithPreservesDeferredStubKey() {
        var inner = (Rejection.Deferred) Rejection.deferred("X is not yet supported", ChildField.SplitTableField.class);
        var prefixed = (Rejection.Deferred) inner.prefixedWith("on Foo.bar: ");
        assertThat(prefixed.summary()).isEqualTo("on Foo.bar: X is not yet supported");
        assertThat(prefixed.stubKey()).isEqualTo(new Rejection.StubKey.VariantClass(ChildField.SplitTableField.class));
    }
}
