package no.sikt.graphitron.rewrite.validation;

import no.sikt.graphitron.rewrite.TestSchemaHelper;
import no.sikt.graphitron.rewrite.ValidationError;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import static no.sikt.graphitron.rewrite.validation.FieldValidationTestHelper.validate;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * R244 ; the single-errors-field invariant on outcome types, enforced by
 * {@code GraphitronSchemaValidator.validateOutcomeTypeShape}. The binary {@code Outcome} witness
 * ({@code Success | ErrorList}) has one error slot, so a type carrying two
 * {@code ChildField.ErrorsField} children has no well-defined success/error fork and is rejected at
 * build time with the typed {@code ErrorChannelWalkerError.MultipleErrorsFields} arm.
 *
 * <p>Unlike the rule 7 / rule 8 / accessor-coverage rejections (raised by the
 * {@code ErrorChannelWalker} per outcome field and surfaced as {@code UnclassifiedField}; see
 * {@code ErrorChannelClassificationTest}), this is a whole-schema validator pass over the classified
 * model independent of transport, so it is driven through {@code validate(schema)} here rather than
 * per-field classification.
 */
@UnitTier
class OutcomeTypeValidationTest {

    private static final String SAK_PAYLOAD_FQN =
        "no.sikt.graphitron.codereferences.dummyreferences.SakPayload";
    private static final String SERVICE_DECL =
        "@service(service: {className: \"no.sikt.graphitron.rewrite.TestServiceStub\", method: \"runSak\"})";

    @Test
    void outcomeTypeWithTwoErrorsFields_validatorReportsMultipleErrorsFields() {
        // Mirrors the proven rule 7 fixture shape (union-typed errors field; liftToErrorsField only
        // fires on a polymorphic return), with a second errors field added so the single-errors-field
        // invariant is violated. Both `errors` and `moreErrors` lift to ChildField.ErrorsField on
        // SakPayload; validateOutcomeTypeShape then groups them by parent and rejects.
        var schema = TestSchemaHelper.buildSchema("""
            type ValidationErr @error(handlers: [{handler: VALIDATION}]) {
                path: [String!]!
                message: String!
            }
            type DbErr @error(handlers: [{handler: DATABASE, sqlState: "23503"}]) {
                path: [String!]!
                message: String!
            }
            union SakError = ValidationErr | DbErr
            type SakPayload @record(record: {className: "%s"}) {
                data: String
                errors: [SakError]
                moreErrors: [SakError]
            }
            type Query { sak: SakPayload %s }
            """.formatted(SAK_PAYLOAD_FQN, SERVICE_DECL));

        assertThat(validate(schema))
            .extracting(ValidationError::message)
            .anySatisfy(m -> assertThat(m)
                .contains("outcome type 'SakPayload' has more than one errors field")
                .contains("errors")
                .contains("moreErrors")
                .contains("exactly one errors field is allowed"));
    }
}
