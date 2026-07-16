package no.sikt.graphitron.rewrite.validation;

import no.sikt.graphitron.rewrite.TestSchemaHelper;
import no.sikt.graphitron.rewrite.ValidationError;
import no.sikt.graphitron.rewrite.model.ChildField;
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
            type SakPayload {
                data: String
                errors: [SakError]
                moreErrors: [SakError]
            }
            type Query { sak: SakPayload %s }
            """.formatted(SERVICE_DECL));

        // Precondition that makes the non-vacuity explicit: both fields independently lift to
        // ChildField.ErrorsField on SakPayload, which is what gives validateOutcomeTypeShape two
        // entries to group. Without this, a future refactor that collapsed them to a single
        // ErrorsField at field-classification time would empty the group and let the validator
        // assertion below pass-by-accident (or go vacuous); pinning it here fails loudly instead.
        assertThat(schema.field("SakPayload", "errors")).isInstanceOf(ChildField.ErrorsField.class);
        assertThat(schema.field("SakPayload", "moreErrors")).isInstanceOf(ChildField.ErrorsField.class);

        assertThat(validate(schema))
            .extracting(ValidationError::message)
            .anySatisfy(m -> assertThat(m)
                .contains("outcome type 'SakPayload' has more than one errors field")
                .contains("errors")
                .contains("moreErrors")
                .contains("exactly one errors field is allowed"));
    }

    @Test
    void outcomeTypeWithSingleErrorsField_isNotRejected() {
        // Negative control: the same shape with exactly one errors field must NOT trip
        // validateOutcomeTypeShape. Proves the rule discriminates (fires on two, not one) rather
        // than over-firing, and keeps the positive case above honestly non-vacuous. (build(SDL) in
        // ErrorChannelClassificationTest does not run GraphitronSchemaValidator, so this discrimination
        // is pinned only here.)
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
            type SakPayload {
                data: String
                errors: [SakError]
            }
            type Query { sak: SakPayload %s }
            """.formatted(SERVICE_DECL));

        assertThat(validate(schema))
            .extracting(ValidationError::message)
            .noneMatch(m -> m.contains("more than one errors field"));
    }

    @Test
    void outcomePayloadWithTableDataField_isNotRejected() {
        // A @table-bound DataLoader data field (BatchedTableField) sibling to a WrapperArm
        // errors field under a root @service payload must validate. The retired allow-list rejected
        // it (BatchedTableField was not on OUTCOME_TYPE_ARM_SWITCHED_DATA_CHANNEL_VARIANTS) even
        // though the field arm-switches correctly inside its generated DataLoader fetcher; a
        // @table-bound DataLoader data field is a generator capability, not an author error. The
        // structural check replacing the allow-list rejects only siblings that would fall through to
        // graphql-java's default PropertyDataFetcher, which a BatchedTableField does not. See the
        // emission counterpart in FetcherPipelineTest.outcomePayload_tableDataField_* and the
        // execution round-trip in GraphQLQueryTest.submitFilmReviewWithFilm_*.
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
            type Language @table(name: "language") { name: String }
            type SakPayload {
                data: String
                language: Language @reference(path: [{key: "film_language_id_fkey"}])
                errors: [SakError]
            }
            type Query { sak: SakPayload %s }
            """.formatted(SERVICE_DECL));

        // Precondition: the @table sibling classifies as a DataLoader-backed BatchedTableField (the
        // variant the allow-list excluded), and the errors field is on the WrapperArm transport, so
        // the structural pass actually exercises the pairing rather than passing vacuously.
        assertThat(schema.field("SakPayload", "language")).isInstanceOf(ChildField.BatchedTableField.class);
        assertThat(schema.field("SakPayload", "errors")).isInstanceOf(ChildField.ErrorsField.class);

        assertThat(validate(schema))
            .extracting(ValidationError::message)
            .noneMatch(m -> m.contains("PropertyDataFetcher")
                || m.contains("arm-switch")
                || m.contains("WrapperArm errors transport requires"));
    }
}
