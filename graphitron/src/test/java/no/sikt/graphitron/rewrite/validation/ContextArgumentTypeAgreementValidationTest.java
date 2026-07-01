package no.sikt.graphitron.rewrite.validation;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.rewrite.GraphitronSchemaValidator;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import no.sikt.graphitron.rewrite.model.Rejection;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L4 validator-tier test for {@code validateContextArgumentTypeAgreement}: the validator surface
 * of the cross-site contextArgument type-agreement classifier check.
 *
 * <p>Mirror of the L2 {@link no.sikt.graphitron.rewrite.ContextArgumentTypeAgreementTest}
 * classifier-side coverage. Where the L2 test asserts on the typed
 * {@link Rejection.AuthorError.TypeConflict} structure, this test asserts on the
 * {@link no.sikt.graphitron.rewrite.ValidationError} that the validator surfaces and on the
 * rendered prose every site contributes one line to.
 */
@PipelineTier
class ContextArgumentTypeAgreementValidationTest {

    @Test
    void validator_drainsConflictsIntoValidationErrors() {
        // Two sites declaring tenantId with disagreeing Java types ({@code String} and
        // {@code Long}). The validator must surface one ValidationError per conflicting name,
        // carrying the typed TypeConflict rejection with both sites attached.
        String sdl = """
            type Language @table(name: "language") { name: String }
            type Query {
                a(cityNames: String @field(name: "name")
                    @condition(condition: {
                        className: "no.sikt.graphitron.rewrite.TestConditionStub",
                        method: "argConditionWithContext"
                    }, contextArguments: ["tenantId"])):
                    [Language!]!
                b(cityNames: String @field(name: "name")
                    @condition(condition: {
                        className: "no.sikt.graphitron.rewrite.TestConditionStub",
                        method: "argConditionTenantIdLong"
                    }, contextArguments: ["tenantId"])):
                    [Language!]!
            }
            """;
        var schema = TestSchemaHelper.buildSchema(sdl);
        var errors = new GraphitronSchemaValidator().validate(schema);

        var typeConflicts = errors.stream()
            .filter(e -> e.rejection() instanceof Rejection.AuthorError.TypeConflict)
            .toList();
        assertThat(typeConflicts).hasSize(1);

        var tc = (Rejection.AuthorError.TypeConflict) typeConflicts.get(0).rejection();
        assertThat(tc.contextArgumentName()).isEqualTo("tenantId");
        assertThat(tc.sites()).hasSize(2);
        assertThat(tc.sites())
            .extracting(s -> s.declared())
            .containsExactlyInAnyOrder(
                ClassName.get(String.class),
                ClassName.get(Long.class));

        // Renderer contract: header sentence + one line per ConflictSite. The L4 snapshot pins
        // the shape so a regression that drops or de-duplicates sites is caught here.
        String rendered = tc.message();
        assertThat(rendered)
            .contains("contextArgument 'tenantId'")
            .contains("argConditionWithContext")
            .contains("argConditionTenantIdLong")
            .contains("java.lang.String")
            .contains("java.lang.Long");
    }
}
