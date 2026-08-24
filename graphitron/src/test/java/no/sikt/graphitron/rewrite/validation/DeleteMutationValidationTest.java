package no.sikt.graphitron.rewrite.validation;

import no.sikt.graphitron.rewrite.ValidationError;
import no.sikt.graphitron.rewrite.model.CallSiteExtraction;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.DeleteRows;
import no.sikt.graphitron.rewrite.model.DmlReturnExpression;
import no.sikt.graphitron.rewrite.model.HelperRef;
import no.sikt.graphitron.rewrite.model.InputArgRef;
import no.sikt.graphitron.rewrite.model.KeyColumn;
import no.sikt.graphitron.rewrite.model.MatchedKey;
import no.sikt.graphitron.rewrite.model.MutationField.DmlTableField;
import no.sikt.graphitron.rewrite.model.OperationMember;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.javapoet.ClassName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
import java.util.Optional;

import static no.sikt.graphitron.rewrite.validation.FieldValidationTestHelper.validate;
import static org.assertj.core.api.Assertions.assertThat;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import no.sikt.graphitron.rewrite.TestFixtures;

@UnitTier
class DeleteMutationValidationTest {

    enum Case implements ValidatorCase {

        VALID("delete mutation field, well-formed, no validation errors",
            new DmlTableField(
                "Mutation", "deleteFilm", null,
                new DmlReturnExpression.EncodedSingle(
                    new HelperRef.Encode(
                        ClassName.get("fake.code.generated", "NodeIdEncoder"),
                        "encodeFilm",
                        List.of(new ColumnRef("film_id", "FILM_ID", "java.lang.Long")))),
                // DELETE carries the slim InputArgRef + the DeleteRows walker carrier on the
                // write arm. filmId covers the PK, so this is an Identified single-row delete.
                new OperationMember.Write.Delete(
                    new InputArgRef("in", "FilmKey",
                        TestFixtures.tableRef("film", "FILM", "Film", List.of()), false),
                    new DeleteRows.Identified(
                        new MatchedKey.PrimaryKey(
                            List.of(new ColumnRef("film_id", "FILM_ID", "java.lang.Long")), "film_pkey"),
                        List.of(new KeyColumn(
                            "filmId",
                            new ColumnRef("film_id", "FILM_ID", "java.lang.Long"),
                            new CallSiteExtraction.Direct(), 0)))),
                Optional.empty()),
            List.of());

        private final String description;
        private final GraphitronField field;
        private final List<String> errors;

        Case(String description, GraphitronField field, List<String> errors) {
            this.description = description;
            this.field = field;
            this.errors = errors;
        }

        @Override public GraphitronField field() { return field; }
        @Override public List<String> errors() { return errors; }
        @Override public String toString() { return description; }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Case.class)
    void deleteMutationFieldValidation(Case tc) {
        assertThat(validate(tc.field()))
            .extracting(ValidationError::message)
            .containsExactlyInAnyOrderElementsOf(tc.errors());
    }

    // Author-facing contract: DELETE -> @table is rejected at classify time (producing an
    // UnclassifiedField), so the validator surfaces a build-time ValidationError with the new
    // message. The model can no longer represent the wrong shape (DmlTableField's compact
    // constructor pairs a Delete arm with Encoded* returns only), so these contracts are pinned
    // SDL-driven rather than by hand-building an illegal field object.

    @org.junit.jupiter.api.Test
    void directReturnTableType_yieldsValidationError() {
        var schema = no.sikt.graphitron.rewrite.TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            input FilmInput { filmId: Int! @field(name: "film_id") }
            type Query { x: String }
            type Mutation { deleteFilm(in: FilmInput!): Film @mutation(typeName: DELETE, table: "film") }
            """);
        assertThat(validate(schema))
            .extracting(ValidationError::message)
            .anyMatch(m -> m.contains("@mutation(typeName: DELETE) return type")
                        && m.contains("(@table)")
                        && m.contains("RETURNING carries only the primary key")
                        && m.contains("return ID"));
    }

    @org.junit.jupiter.api.Test
    void payloadCarrierWithTableElement_yieldsValidationError() {
        var schema = no.sikt.graphitron.rewrite.TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            input FilmInput { filmId: Int! @field(name: "film_id") }
            type DeletedFilmsPayload { deleted: [Film!] }
            type Query { x: String }
            type Mutation { deleteFilms(in: [FilmInput!]!): DeletedFilmsPayload @mutation(typeName: DELETE, table: "film") }
            """);
        assertThat(validate(schema))
            .extracting(ValidationError::message)
            .anyMatch(m -> m.contains("@table-element data field")
                        && m.contains("RETURNING carries only the primary key")
                        && m.contains("ID-typed data field"));
    }
}
