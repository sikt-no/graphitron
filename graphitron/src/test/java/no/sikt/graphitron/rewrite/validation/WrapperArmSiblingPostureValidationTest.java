package no.sikt.graphitron.rewrite.validation;

import no.sikt.graphitron.rewrite.TestSchemaHelper;
import no.sikt.graphitron.rewrite.ValidationError;
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
 * graphitron's own non-arm-switching emit paths.
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

    private static final String UNION_PARTICIPANTS = """
        type Inventory @table(name: "inventory") { inventoryId: Int! @field(name: "inventory_id") }
        type Content @table(name: "content") { contentId: Int! @field(name: "content_id") }
        union FilmReferrer = Inventory | Content
        """;

    @Test
    void serviceChildBesideWrapperArmErrorsField_isRejected() {
        // A child @service field's key source reads env.getSource() without unwrapping the
        // Outcome wrapper, so beside a WrapperArm errors field it is a request-time
        // ClassCastException; the posture rule turns it into a build-time rejection.
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
        // @service leaf (third posture bucket) and the errors field rides the WrapperArm transport.
        assertThat(schema.field("Aggregated", "filmsViaService"))
            .isInstanceOf(ChildField.ServiceTableField.class);
        var errorsField = (ChildField.ErrorsField) schema.field("Aggregated", "errors");
        assertThat(errorsField.transport()).isInstanceOf(ChildField.Transport.WrapperArm.class);

        assertThat(validate(schema))
            .extracting(ValidationError::message)
            .anySatisfy(m -> assertThat(m)
                .contains("Aggregated.filmsViaService")
                .contains("ServiceTableField")
                .contains("cannot sit beside the errors field")
                .contains("ClassCastException"));
    }

    @Test
    void polymorphicChildrenBesideWrapperArmErrorsField_areNotRejected() {
        // The fixed combination: polymorphic children consume the parent-source binding seam
        // (first posture bucket), so an errors-bearing payload holding them must validate. This is
        // the reporter's shape: a Pojo payload with a typed hub accessor, a single-valued
        // polymorphic child, and a WrapperArm errors union.
        var schema = TestSchemaHelper.buildSchema(ERROR_TYPES + UNION_PARTICIPANTS + """
            type SinglePayloadType {
              film: FilmReferrer
              errors: [SakError]
            }
            type Query {
              sp: SinglePayloadType
                @service(service: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyService", method: "makeAccessorSinglePayload"})
            }
            """);

        assertThat(schema.field("SinglePayloadType", "film"))
            .isInstanceOf(ChildField.UnionField.class);
        var errorsField = (ChildField.ErrorsField) schema.field("SinglePayloadType", "errors");
        assertThat(errorsField.transport()).isInstanceOf(ChildField.Transport.WrapperArm.class);

        assertThat(validate(schema))
            .extracting(ValidationError::message)
            .noneMatch(m -> m.contains("cannot sit beside the errors field"));
    }

    @Test
    void batchedPolymorphicChildBesideWrapperArmErrorsField_isNotRejected() {
        // The record-backed batched form: a @service payload backed by the jOOQ record itself
        // (JooqTableRecordType), a list-cardinality polymorphic child (BatchedUnionField, the
        // KeyLift.FkColumns lift under the wrapper), and a WrapperArm errors union.
        var schema = TestSchemaHelper.buildSchema(ERROR_TYPES + UNION_PARTICIPANTS + """
            type Film {
              referrers: [FilmReferrer!]!
              errors: [SakError]
            }
            type Query {
              film: Film
                @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "getFilm"})
            }
            """);

        assertThat(schema.field("Film", "referrers"))
            .isInstanceOf(ChildField.BatchedUnionField.class);
        var errorsField = (ChildField.ErrorsField) schema.field("Film", "errors");
        assertThat(errorsField.transport()).isInstanceOf(ChildField.Transport.WrapperArm.class);

        assertThat(validate(schema))
            .extracting(ValidationError::message)
            .noneMatch(m -> m.contains("cannot sit beside the errors field"));
    }
}
