package no.sikt.graphitron.rewrite.validation;

import no.sikt.graphitron.rewrite.ValidationError;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.JoinStep;
import no.sikt.graphitron.rewrite.model.ChildField.NodeIdReferenceField;
import no.sikt.graphitron.rewrite.model.FieldWrapper;
import no.sikt.graphitron.rewrite.model.ReturnTypeRef;
import no.sikt.graphitron.rewrite.model.TableRef;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static no.sikt.graphitron.rewrite.validation.FieldValidationTestHelper.validate;
import static org.assertj.core.api.Assertions.assertThat;

class NodeIdReferenceFieldValidationTest {

    enum Case implements ValidatorCase {

        IMPLICIT_SINGLE_FK("exactly one FK between tables — implicit join (R5: implemented, no errors)",
            new NodeIdReferenceField("Inventory", "filmId", null, "Film",
                new ReturnTypeRef.TableBoundReturnType("Film", new TableRef("film", "FILM", "Film", List.of()), new FieldWrapper.Single(true)),
                new TableRef("inventory", "INVENTORY", "Inventory", List.of()),
                null, List.of(),
                List.of()),
            List.of()),

        WITH_EXPLICIT_PATH("explicit FK path leading to the correct table (R5: implemented, no errors)",
            new NodeIdReferenceField("Film", "languageId", null, "Language",
                new ReturnTypeRef.TableBoundReturnType("Language", new TableRef("language", "LANGUAGE", "Language", List.of()), new FieldWrapper.Single(true)),
                new TableRef("film", "FILM", "Film", List.of()),
                null, List.of(),
                List.of(new JoinStep.FkJoin("film_language_id_fkey", "", null, List.of(), new TableRef("language", "", "", List.of()), List.of(), null, ""))),
            List.of()),

        PATH_WRONG_TABLE("explicit FK path leading to the wrong table — one validation error",
            new NodeIdReferenceField("Film", "languageId", null, "Language",
                new ReturnTypeRef.TableBoundReturnType("Language", new TableRef("language", "LANGUAGE", "Language", List.of()), new FieldWrapper.Single(true)),
                new TableRef("film", "FILM", "Film", List.of()),
                null, List.of(),
                List.of(new JoinStep.FkJoin("sequel_fkey", "", null, List.of(), new TableRef("film", "", "", List.of()), List.of(), null, ""))),
            List.of("Field 'Film.languageId': @reference path does not lead to the table of type 'Language'")),

        NULL_PARENT_TABLE_IMPLICIT("null parentTable with empty path — FK check silently skipped (R5: implemented, no errors)",
            new NodeIdReferenceField("SomeResult", "filmId", null, "Film",
                new ReturnTypeRef.TableBoundReturnType("Film", new TableRef("film", "FILM", "Film", List.of()), new FieldWrapper.Single(true)),
                null, // non-table parent — null guard in validator skips the FK count check
                null, List.of(),
                List.of()),
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
    void nodeIdReferenceFieldValidation(Case tc) {
        assertThat(validate(tc.field()))
            .extracting(ValidationError::message)
            .containsExactlyInAnyOrderElementsOf(tc.errors());
    }
}
