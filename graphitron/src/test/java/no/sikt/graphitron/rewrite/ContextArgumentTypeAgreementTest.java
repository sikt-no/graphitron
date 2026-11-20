package no.sikt.graphitron.rewrite;

import com.palantir.javapoet.ClassName;
import no.sikt.graphitron.model.diagnostics.Rejection;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L2 classifier tests for {@link ContextArgumentClassifier}: the cross-site
 * {@code contextArgument} type-agreement check.
 *
 * <ul>
 *   <li><b>Accepted</b>: two directive sites reference {@code tenantId} with the same Java type
 *       ({@code String}); the classifier records a single resolved entry and produces no
 *       conflict.</li>
 *   <li><b>Rejected</b>: two sites reference {@code tenantId} with different Java types
 *       ({@code String} and {@code Long}); the classifier produces a typed
 *       {@link Rejection.AuthorError.TypeConflict} carrying both sites.</li>
 * </ul>
 */
@PipelineTier
class ContextArgumentTypeAgreementTest {

    @Test
    void agreement_acceptsSameTypeAcrossSites() {
        // Two directive sites of different kinds — an arg-level @condition (String tenantId)
        // and a field-level @condition on another field (String tenantId) — both declare
        // contextArguments: ["tenantId"] against String-typed Java parameters. The classifier
        // should record one ResolvedContextArg.
        String sdl = """
            type Language @table(name: "language") { name: String }
            type Query {
                languages(cityNames: String @field(name: "name")
                    @condition(condition: {
                        className: "no.sikt.graphitron.rewrite.TestConditionStub",
                        method: "argConditionWithContext"
                    }, contextArguments: ["tenantId"])):
                    [Language!]!
                more(otherNames: String @field(name: "name")
                    @condition(condition: {
                        className: "no.sikt.graphitron.rewrite.TestConditionStub",
                        method: "argConditionWithContext"
                    }, contextArguments: ["tenantId"])):
                    [Language!]!
            }
            """;
        var schema = TestSchemaHelper.buildSchema(sdl);
        var classification = ContextArgumentClassifier.classify(schema);

        assertThat(classification.conflicts()).isEmpty();
        var resolved = classification.resolved().get("tenantId");
        assertThat(resolved).as("tenantId must resolve when both sites agree on String").isNotNull();
        assertThat(resolved.name()).isEqualTo("tenantId");
        assertThat(resolved.javaType()).isEqualTo(ClassName.get(String.class));
        assertThat(resolved.sites()).hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    void agreement_rejectsDisagreeingTypesAcrossSites() {
        // Fixture: argConditionWithContext declares tenantId: String;
        // argConditionTenantIdLong declares tenantId: Long. Two arg-level @condition sites with
        // the same contextArgument name but disagreeing Java types — classifier surfaces a
        // TypeConflict.
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
        var classification = ContextArgumentClassifier.classify(schema);

        // Conflicting name does not land in resolved; surfaces as a TypeConflict rejection.
        assertThat(classification.resolved()).doesNotContainKey("tenantId");
        assertThat(classification.conflicts()).hasSize(1);

        var conflict = (Rejection.AuthorError.TypeConflict) classification.conflicts().get(0);
        assertThat(conflict.contextArgumentName()).isEqualTo("tenantId");
        assertThat(conflict.sites()).hasSize(2);
        assertThat(conflict.sites())
            .extracting(s -> s.declared())
            .containsExactlyInAnyOrder(
                String.class.getName(),
                Long.class.getName());
    }
}
