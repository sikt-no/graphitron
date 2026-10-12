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
 * error arm renders every data field {@code null}, so the nullable-success-projection rule applies
 * to these carriers exactly as it does to {@code @service} ones, and the sibling rules keyed on the
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
        assertThat(schema.field("Mutation", fieldName))
            .isInstanceOfSatisfying(GraphitronField.UnclassifiedField.class, u -> assertThat(u.rejection())
                .isEqualTo(new ErrorChannelWalkerError.NonNullableSuccessProjectionField(payload, dataField)));
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
    void dmlPayloadWithErrors_nonNullListDataField_isRejected() {
        var schema = TestSchemaHelper.buildSchema(ERRORS + FILM + """
            type FilmsPayload { films: [Film!]! errors: [CarrierError] }
            type Mutation { createFilms(in: [FilmInput!]!): FilmsPayload @mutation(typeName: INSERT, table: "film") }
            """);
        assertRejectedForNonNullData(schema, "createFilms", "FilmsPayload", "films");
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
        assertThat(((ChildField.ErrorsField) schema.field("FilmsPayload", "errors")).transport())
            .isInstanceOf(ChildField.Transport.WrapperArm.class);
        assertThat(validate(schema)).isEmpty();
    }

    @Test
    void routinePayloadWithErrors_nonNullListDataField_isRejected() {
        // The routine classifier's own rule exempts a list data field (the zero-row re-read is an
        // empty list); with an errors field the Outcome error arm renders it null, so the shared
        // nullable-success-projection rule rejects the outer '!'.
        var schema = TestSchemaHelper.buildSchema(ERRORS + RENTAL + """
            type RentFilmPayload { rentals: [Rental!]! errors: [CarrierError] }
            type Mutation {
              rentFilm(inventoryId: Int!, customerId: Int!): RentFilmPayload
                @routine(name: "rent_film", argMapping: "pInventoryId: inventoryId, pCustomerId: customerId")
            }
            """);
        assertRejectedForNonNullData(schema, "rentFilm", "RentFilmPayload", "rentals");
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
        assertThat(((ChildField.ErrorsField) schema.field("FilmsPayload", "errors")).transport())
            .isInstanceOf(ChildField.Transport.WrapperArm.class);
        assertThat(validate(schema)).isEmpty();
        assertThat(schema.warnings())
            .extracting(BuildWarning::message)
            .anySatisfy(m -> assertThat(m)
                .contains("FilmsPayload.errors")
                .contains("$errors")
                .contains("no longer needed"));
    }
}
