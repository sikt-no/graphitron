package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField;
import no.sikt.graphitron.rewrite.model.WireCoercionError;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R261 Slice 1 acceptance: the generation-time wire-coercion cast guard across the {@code @service}
 * arg-classification sites (A, B, E). Before R261 each site fell through to
 * {@code CallSiteExtraction.Direct} and emitted a raw {@code (DeclaredType) wireValue} cast that
 * compiled cleanly and {@code ClassCastException}d (or, for enums, {@code IllegalArgumentException}d)
 * on the first request. The classifier now confirms graphql-java's coercion output is assignable to
 * the declared type before emitting {@code Direct}; a mismatch surfaces as a typed
 * {@link WireCoercionError} on the field's verdict, pinned here by the arm's stable {@code lspCode()}
 * rather than prose (per the R246/R256 precedent).
 *
 * <p>Sites C ({@code @condition}) and D ({@code @externalField}) are out of scope for Slice 1 — their
 * dimensional wire-coercion channel is not yet pinned — so the {@code @tableMethod} /
 * {@code @condition} argument path keeps its legacy extraction; see the carved-out follow-up
 * {@code reject-wire-coercion-nonservice-sites}.
 */
@PipelineTier
class WireCoercionCastGuardPipelineTest {

    private static final String ASSIGNABILITY_CODE = "graphitron.wire-coercion.assignability";
    private static final String ENUM_DIVERGENCE_CODE = "graphitron.wire-coercion.enum-constant-divergence";

    private static WireCoercionError rejectionOf(no.sikt.graphitron.rewrite.model.GraphitronField field) {
        assertThat(field).isInstanceOf(UnclassifiedField.class);
        var rejection = ((UnclassifiedField) field).rejection();
        assertThat(rejection)
            .as("the field must be rejected with a typed WireCoercionError, not a generated raw cast")
            .isInstanceOf(WireCoercionError.class);
        return (WireCoercionError) rejection;
    }

    // ===== Site B: @service scalar argument =====

    @Test
    void serviceScalarArg_idBoundToLong_rejectedWithAssignability() {
        var schema = TestSchemaHelper.buildSchema("""
            type Query {
              film(id: ID): String
                @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "wireIdAsLong"})
            }
            """);
        var rejection = rejectionOf(schema.field("Query", "film"));
        assertThat(rejection).isInstanceOf(WireCoercionError.Assignability.class);
        var a = (WireCoercionError.Assignability) rejection;
        assertThat(a.lspCode()).isEqualTo(ASSIGNABILITY_CODE);
        assertThat(a.coercionOutputType()).isEqualTo("java.lang.String");
        assertThat(a.declaredType()).isEqualTo("java.lang.Long");
    }

    @Test
    void serviceScalarArg_idBoundToString_classifiesCleanly() {
        // Non-regression: ID → String is a true wire pass-through; the predicate must keep the
        // Direct arm, not over-reject.
        var schema = TestSchemaHelper.buildSchema("""
            type Query {
              film(id: ID): String
                @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "wireIdAsString"})
            }
            """);
        assertThat(schema.field("Query", "film")).isNotInstanceOf(UnclassifiedField.class);
    }

    @Test
    void serviceScalarArg_customScalarMatchingResolution_classifiesCleanly() {
        // Custom-scalar non-regression: the predicate consults the @scalarType-resolved Java type, so
        // a declared type equal to the resolution (Money) is Direct, not a spurious Assignability.
        var schema = TestSchemaHelper.buildSchema("""
            scalar Money @scalarType(scalar: "no.sikt.graphitron.rewrite.scalarfixture.ScalarConstants.MONEY")
            type Query {
              price(amount: Money): String
                @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "wireMoney"})
            }
            """);
        assertThat(schema.field("Query", "price")).isNotInstanceOf(UnclassifiedField.class);
    }

    // ===== Site A: @service input-bean scalar field =====

    @Test
    void inputBeanScalarField_intBoundToLong_rejectedWithAssignability() {
        var schema = TestSchemaHelper.buildSchema("""
            input WireLongInput { filmId: Int }
            type Query {
              useBean(input: WireLongInput): String
                @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "useLongBean"})
            }
            """);
        var rejection = rejectionOf(schema.field("Query", "useBean"));
        assertThat(rejection).isInstanceOf(WireCoercionError.Assignability.class);
        var a = (WireCoercionError.Assignability) rejection;
        assertThat(a.lspCode()).isEqualTo(ASSIGNABILITY_CODE);
        assertThat(a.coercionOutputType()).isEqualTo("java.lang.Integer");
        assertThat(a.declaredType()).isEqualTo("java.lang.Long");
    }

    // ===== Site E: @service input-bean enum field with a divergent value name =====

    @Test
    void inputBeanEnumField_divergentValueName_rejectedWithEnumConstantDivergence() {
        var schema = TestSchemaHelper.buildSchema("""
            enum WireRating { G PG PGThirteen }
            input WireEnumInput { rating: WireRating }
            type Query {
              useBean(input: WireEnumInput): String
                @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "useEnumBean"})
            }
            """);
        var rejection = rejectionOf(schema.field("Query", "useBean"));
        assertThat(rejection).isInstanceOf(WireCoercionError.EnumConstantDivergence.class);
        var d = (WireCoercionError.EnumConstantDivergence) rejection;
        assertThat(d.lspCode()).isEqualTo(ENUM_DIVERGENCE_CODE);
        assertThat(d.divergentSdlValues()).contains("PGThirteen");
    }

    @Test
    void inputBeanEnumField_allValuesMatch_classifiesCleanly() {
        // Non-regression: every SDL enum value maps to a Java constant, so EnumValueOf is emitted
        // without a divergence reject.
        var schema = TestSchemaHelper.buildSchema("""
            enum WireRating { G PG PG_13 R NC_17 }
            input WireEnumInput { rating: WireRating }
            type Query {
              useBean(input: WireEnumInput): String
                @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "useEnumBean"})
            }
            """);
        assertThat(schema.field("Query", "useBean")).isNotInstanceOf(UnclassifiedField.class);
    }
}
