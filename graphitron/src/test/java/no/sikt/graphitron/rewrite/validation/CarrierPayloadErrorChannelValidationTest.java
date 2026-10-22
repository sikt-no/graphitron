package no.sikt.graphitron.rewrite.validation;

import no.sikt.graphitron.model.diagnostics.BuildWarning;
import no.sikt.graphitron.model.diagnostics.ErrorChannelWalkerError;
import no.sikt.graphitron.model.diagnostics.ValidationError;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.MutationField;
import no.sikt.graphitron.rewrite.model.SourceEnvelope;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import static no.sikt.graphitron.rewrite.validation.FieldValidationTestHelper.validate;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The build-time rules a DML or {@code @routine} payload carrier answers to once its errors ride
 * the typed {@code Outcome} wrapper, the transport root {@code @service} payloads already use. The
 * error arm renders a nullable data field {@code null} and a non-null plain list {@code []}, so the
 * error-arm rule (a data field must be nullable or a non-null plain list) applies to these carriers
 * exactly as it does to {@code @service} ones, and the sibling rules keyed on the
 * {@code WrapperArm} transport ({@code validateOutcomeChildArmSwitch},
 * {@code validateWrapperArmSiblingPosture}) govern the carrier's data fields.
 */
@UnitTier
class CarrierPayloadErrorChannelValidationTest {

    private static final String ERRORS = """
        type SimpleErr @error(handlers: [{handler: GENERIC, className: "java.lang.RuntimeException"}]) {
            path: [String!]!
            message: String!
        }
        union CarrierError = SimpleErr
        """;

    private static final String FILM = """
        type Film @table(name: "film") { title: String }
        input FilmInput { title: String }
        type Query { films: [Film] }
        """;

    private static final String RENTAL = """
        type Rental @table(name: "rental") { rentalId: Int! @field(name: "rental_id") }
        type Query { rental: Rental }
        """;

    private static void assertRejectedForNonNullData(GraphitronSchema schema, String fieldName,
            String payload, String dataField) {
        var expected = new ErrorChannelWalkerError.NonNullableSuccessProjectionField(payload, dataField);
        assertThat(schema.field("Mutation", fieldName))
            .isInstanceOfSatisfying(GraphitronField.UnclassifiedField.class, u -> assertThat(u.rejection())
                .isEqualTo(expected));
        // The rule has one home, the classifier: the payload still classifies when its producer is
        // rejected, so a second check over the payload's leaves would report the field again.
        assertThat(validate(schema))
            .filteredOn(e -> e.rejection() instanceof ErrorChannelWalkerError.NonNullableSuccessProjectionField)
            .singleElement()
            .satisfies(e -> {
                assertThat(e.coordinate()).isEqualTo("Mutation." + fieldName);
                assertThat(e.message()).contains(expected.message());
            });
    }

    @Test
    void dmlPayloadWithErrors_nonNullSingleDataField_isRejected() {
        var schema = TestSchemaHelper.buildSchema(ERRORS + FILM + """
            type FilmPayload { film: Film! errors: [CarrierError] }
            type Mutation { createFilm(in: FilmInput!): FilmPayload @mutation(typeName: INSERT, table: "film") }
            """);
        assertRejectedForNonNullData(schema, "createFilm", "FilmPayload", "film");
    }

    @Test
    void dmlPayloadWithErrors_nonNullListDataField_isAccepted() {
        // A non-null plain list answers [] on the error arm, so it has a value to give there.
        var schema = TestSchemaHelper.buildSchema(ERRORS + FILM + """
            type FilmsPayload { films: [Film!]! errors: [CarrierError] }
            type Mutation { createFilms(in: [FilmInput!]!): FilmsPayload @mutation(typeName: INSERT, table: "film") }
            """);
        assertThat(schema.field("Mutation", "createFilms"))
            .isInstanceOf(MutationField.MutationBulkDmlRecordField.class);
        assertThat(validate(schema)).isEmpty();
    }

    @Test
    void dmlPayloadWithErrors_nonNullListOfNullableDataField_isAccepted() {
        var schema = TestSchemaHelper.buildSchema(ERRORS + FILM + """
            type FilmsPayload { films: [Film]! errors: [CarrierError] }
            type Mutation { createFilms(in: [FilmInput!]!): FilmsPayload @mutation(typeName: INSERT, table: "film") }
            """);
        assertThat(schema.field("Mutation", "createFilms"))
            .isInstanceOf(MutationField.MutationBulkDmlRecordField.class);
        assertThat(validate(schema)).isEmpty();
    }

    @Test
    void dmlPayloadWithErrors_nullableDataField_validatesUnderTheWrapperSiblingRules() {
        // The positive case the sibling rules must admit: the record-sourced data field narrows
        // Outcome.Success through the parent-source binding seam.
        var schema = TestSchemaHelper.buildSchema(ERRORS + FILM + """
            type FilmsPayload { films: [Film!] errors: [CarrierError] }
            type Mutation { createFilms(in: [FilmInput!]!): FilmsPayload @mutation(typeName: INSERT, table: "film") }
            """);
        assertThat(schema.field("Mutation", "createFilms"))
            .isInstanceOf(MutationField.MutationBulkDmlRecordField.class);
        assertThat(((ChildField.ErrorsField) schema.field("FilmsPayload", "errors"))).isInstanceOf(no.sikt.graphitron.rewrite.model.ChildField.ErrorsField.class);
        assertThat(validate(schema)).isEmpty();
    }

    @Test
    void routinePayloadWithErrors_nonNullListDataField_isAccepted() {
        // The routine classifier's own rule exempts a list data field (the zero-row re-read is an
        // empty list); with an errors field the Outcome error arm renders it [] too, so the shared
        // error-arm rule admits the outer '!' as well.
        var schema = TestSchemaHelper.buildSchema(ERRORS + RENTAL + """
            type RentFilmPayload { rentals: [Rental!]! errors: [CarrierError] }
            type Mutation {
              rentFilm(inventoryId: Int!, customerId: Int!): RentFilmPayload
                @routine(name: "rent_film", argMapping: "pInventoryId: inventoryId, pCustomerId: customerId")
            }
            """);
        assertThat(schema.field("Mutation", "rentFilm"))
            .isInstanceOf(MutationField.MutationRoutineWriteRecordField.class);
        assertThat(validate(schema)).isEmpty();
    }

    @Test
    void routinePayloadWithErrors_nonNullSingleDataField_isRejected() {
        // The routine classifier's zero-row rule fires first for a non-null single; either way
        // the producer is rejected exactly once.
        var schema = TestSchemaHelper.buildSchema(ERRORS + RENTAL + """
            type RentFilmPayload { rental: Rental! errors: [CarrierError] }
            type Mutation {
              rentFilm(inventoryId: Int!, customerId: Int!): RentFilmPayload
                @routine(name: "rent_film", argMapping: "pInventoryId: inventoryId, pCustomerId: customerId")
            }
            """);
        assertThat(schema.field("Mutation", "rentFilm")).isInstanceOf(GraphitronField.UnclassifiedField.class);
        assertThat(validate(schema)).filteredOn(e -> e.coordinate().startsWith("RentFilmPayload")).isEmpty();
        assertThat(validate(schema)).filteredOn(e -> e.coordinate().equals("Mutation.rentFilm")).hasSize(1);
    }

    @Test
    void routinePayloadWithoutErrors_nonNullListDataField_stillBuilds() {
        var schema = TestSchemaHelper.buildSchema(RENTAL + """
            type RentFilmPayload { rentals: [Rental!]! }
            type Mutation {
              rentFilm(inventoryId: Int!, customerId: Int!): RentFilmPayload
                @routine(name: "rent_film", argMapping: "pInventoryId: inventoryId, pCustomerId: customerId")
            }
            """);
        assertThat(schema.field("Mutation", "rentFilm"))
            .isInstanceOf(MutationField.MutationRoutineWriteRecordField.class);
        assertThat(validate(schema)).isEmpty();
    }

    @Test
    void deletePayloadWithErrors_idListDataField_isAccepted() {
        // The DELETE id carrier reads its RETURNING rows through the envelope fork, so it may sit
        // beside a WrapperArm errors field; the envelope is what tells its fetcher to narrow.
        var schema = TestSchemaHelper.buildSchema(ERRORS + """
            type Film implements Node @table(name: "film") @node { id: ID! @nodeId filmId: Int! @field(name: "film_id") }
            input FilmDeleteInput { filmId: Int! @field(name: "film_id") }
            type DeletedFilmsPayload { deletedIds: [ID!] @nodeId(typeName: "Film") errors: [CarrierError] }
            type Query { films: [Film] }
            type Mutation { deleteFilms(in: [FilmDeleteInput!]!): DeletedFilmsPayload @mutation(typeName: DELETE, table: "film") }
            """);
        assertThat(schema.field("Mutation", "deleteFilms"))
            .isInstanceOf(MutationField.MutationBulkDmlRecordField.class);
        assertThat(schema.field("DeletedFilmsPayload", "deletedIds"))
            .isInstanceOfSatisfying(ChildField.SingleRecordIdFieldFromReturning.class,
                id -> assertThat(id.envelope()).isEqualTo(SourceEnvelope.OUTCOME_SUCCESS));
        assertThat(validate(schema)).extracting(ValidationError::message).isEmpty();
    }

    @Test
    void errorsSigilOnACarrierErrorsField_buildsWithAWarning() {
        var schema = TestSchemaHelper.buildSchema(ERRORS + FILM + """
            type FilmsPayload { films: [Film!] errors: [CarrierError] @field(name: "$errors") }
            type Mutation { createFilms(in: [FilmInput!]!): FilmsPayload @mutation(typeName: INSERT, table: "film") }
            """);
        assertThat(((ChildField.ErrorsField) schema.field("FilmsPayload", "errors"))).isInstanceOf(no.sikt.graphitron.rewrite.model.ChildField.ErrorsField.class);
        assertThat(validate(schema)).isEmpty();
        assertThat(schema.warnings())
            .extracting(BuildWarning::message)
            .anySatisfy(m -> assertThat(m)
                .contains("FilmsPayload.errors")
                .contains("$errors")
                .contains("no longer needed"));
    }
}
