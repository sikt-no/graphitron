package no.sikt.graphitron.rewrite.validation;

import no.sikt.graphitron.rewrite.ArgumentRef;
import no.sikt.graphitron.rewrite.ValidationError;
import no.sikt.graphitron.rewrite.model.CallSiteExtraction;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.DmlReturnExpression;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.MutationField.MutationInsertTableField;
import no.sikt.graphitron.rewrite.model.TableRef;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
import java.util.Optional;

import static no.sikt.graphitron.rewrite.validation.FieldValidationTestHelper.validate;
import static org.assertj.core.api.Assertions.assertThat;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;

@UnitTier
class MutationInsertTableFieldValidationTest {

    enum Case implements ValidatorCase {

        VALID("insert mutation field, well-formed, no validation errors",
            new MutationInsertTableField(
                "Mutation", "createFilm", null,
                new DmlReturnExpression.ProjectedSingle("Film"),
                ArgumentRef.InputTypeArg.TableInputArg.of(
                    "in", "FilmInput", true, false,
                    new TableRef("film", "FILM", "Film", List.of()),
                    List.of(),
                    Optional.empty(),
                    List.of()),
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
    void insertMutationFieldValidation(Case tc) {
        assertThat(validate(tc.field()))
            .extracting(ValidationError::message)
            .containsExactlyInAnyOrderElementsOf(tc.errors());
    }
}
