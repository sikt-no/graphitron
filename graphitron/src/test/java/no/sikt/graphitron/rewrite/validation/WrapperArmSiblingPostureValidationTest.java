package no.sikt.graphitron.rewrite.validation;

import no.sikt.graphitron.rewrite.TestSchemaHelper;
import no.sikt.graphitron.model.diagnostics.ValidationError;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import static no.sikt.graphitron.rewrite.validation.FieldValidationTestHelper.validate;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The parent-source posture rule,
 * {@code GraphitronSchemaValidator.validateWrapperArmSiblingPosture}: a leaf declared in
 * {@code ParentSourceBinding.WRAPPER_INADMISSIBLE_LEAVES} reads its parent's backing object
 * without the parent-source binding seam, so beside a {@code WrapperArm} errors field its read
 * would land on the {@code Outcome} object itself and throw {@code ClassCastException} on every
 * request; the rule rejects the combination at build time instead. The sibling invariant on the
 * {@code PropertyDataFetcher} registration-escape family stays with
 * {@code validateOutcomeChildArmSwitch} ({@code OutcomeTypeValidationTest}); this rule covers
 * graphitron's own non-arm-switching emit paths. A child {@code @service} is not one of them: its
 * frame reads the key off the {@code Outcome}'s value.
 */
@UnitTier
class WrapperArmSiblingPostureValidationTest {

    private static final String ERROR_TYPES = """
        type ValidationErr @error(handlers: [{handler: VALIDATION}]) {
            path: [String!]!
            message: String!
        }
        type DbErr @error(handlers: [{handler: DATABASE, sqlState: "23503"}]) {
            path: [String!]!
            message: String!
        }
        union SakError = ValidationErr | DbErr
        """;

    @Test
    void serviceChildBesideWrapperArmErrorsField_isNotRejected() {
        // A child @service field's frame is emitted from the store, which reads its key off the
        // Outcome's value where the parent is one and answers the field's error arm otherwise, so
        // beside a WrapperArm errors field it is accepted.
        var schema = TestSchemaHelper.buildSchema(ERROR_TYPES + """
            type Film @table(name: "film") { title: String }
            type Aggregated {
              filmsViaService: [Film!]!
                @service(service: {className: "no.sikt.graphitron.rewrite.generators.TestFilmService", method: "getFilmsMappedByRecord"})
              errors: [SakError]
            }
            type Query {
              aggregated: Aggregated
                @service(service: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyService", method: "makeLanguageKeyed"})
            }
            """);

        // Preconditions keeping the pairing non-vacuous: the data field classifies as the child
        // @service leaf and the errors field rides the WrapperArm transport.
        assertThat(schema.field("Aggregated", "filmsViaService"))
            .isInstanceOf(ChildField.ServiceTableField.class);
        var errorsField = (ChildField.ErrorsField) schema.field("Aggregated", "errors");
        assertThat(errorsField).isInstanceOf(no.sikt.graphitron.rewrite.model.ChildField.ErrorsField.class);

        assertThat(validate(schema))
            .extracting(ValidationError::message)
            .noneMatch(m -> m.contains("cannot sit beside the errors field"));
    }
}
