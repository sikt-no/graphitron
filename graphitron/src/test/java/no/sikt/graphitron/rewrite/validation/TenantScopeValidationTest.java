package no.sikt.graphitron.rewrite.validation;

import no.sikt.graphitron.common.configuration.TestConfiguration;
import no.sikt.graphitron.rewrite.GraphitronSchemaValidator;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import no.sikt.graphitron.rewrite.model.Rejection;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L4 validator-tier test for {@code validateTenantBindings}' catalog half: the validator surface
 * of the tenant-scope classification computed at catalog load.
 *
 * <p>Mirror of the L2 {@link no.sikt.graphitron.rewrite.TenantScopeClassifierTest}
 * classifier-side coverage, shape-parallel to
 * {@link ContextArgumentTypeAgreementValidationTest}: where the L2 test asserts on the typed
 * rejection structure, this test asserts that the validator drains the classification's
 * conflicts into {@link no.sikt.graphitron.rewrite.ValidationError}s (at {@code <schema>};
 * a tenant-column defect has no SDL coordinate).
 */
@PipelineTier
class TenantScopeValidationTest {

    private static final String TRIVIAL_SDL = """
        type Language @table(name: "language") { name: String }
        type Query { languages: [Language!]! }
        """;

    @Test
    void validator_drainsTenantColumnTypeDisagreementIntoValidationErrors() {
        var ctx = TestConfiguration.testContext().withTenantColumn("active");
        var schema = TestSchemaHelper.buildSchema(TRIVIAL_SDL, ctx);

        var errors = new GraphitronSchemaValidator().validate(schema);

        var disagreements = errors.stream()
            .filter(e -> e.rejection() instanceof Rejection.AuthorError.TenantColumnTypeDisagreement)
            .toList();
        assertThat(disagreements).hasSize(1);
        var rejection = (Rejection.AuthorError.TenantColumnTypeDisagreement)
            disagreements.get(0).rejection();
        assertThat(rejection.columnName()).isEqualTo("active");
        assertThat(rejection.message())
            .contains("disagreeing Java types")
            .contains("public.staff")
            .contains("public.customer");
    }

    @Test
    void validator_drainsUnknownTenantColumnIntoValidationErrors() {
        var ctx = TestConfiguration.testContext().withTenantColumn("no_such_column");
        var schema = TestSchemaHelper.buildSchema(TRIVIAL_SDL, ctx);

        var errors = new GraphitronSchemaValidator().validate(schema);

        assertThat(errors)
            .anyMatch(e -> e.rejection() instanceof Rejection.AuthorError.UnknownName u
                && u.attempt().equals("no_such_column"));
    }

    @Test
    void agreedTenantColumnValidatesClean() {
        var ctx = TestConfiguration.testContext().withTenantColumn("k1");
        var schema = TestSchemaHelper.buildSchema(TRIVIAL_SDL, ctx);

        assertThat(new GraphitronSchemaValidator().validate(schema)).isEmpty();
    }

    @Test
    void noTenantColumnValidatesCleanWithNoAxis() {
        var schema = TestSchemaHelper.buildSchema(TRIVIAL_SDL);

        assertThat(schema.tenantScopes())
            .isSameAs(no.sikt.graphitron.rewrite.model.TenantScopes.None.INSTANCE);
        assertThat(new GraphitronSchemaValidator().validate(schema)).isEmpty();
    }
}
