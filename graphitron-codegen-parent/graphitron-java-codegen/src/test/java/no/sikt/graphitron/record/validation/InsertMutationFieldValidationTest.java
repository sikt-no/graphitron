package no.sikt.graphitron.record.validation;

import no.sikt.graphitron.record.ValidationError;
import no.sikt.graphitron.record.field.GraphitronField;
import no.sikt.graphitron.record.field.InsertMutationField;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static no.sikt.graphitron.record.validation.FieldValidationTestHelper.inQuerySchema;
import static no.sikt.graphitron.record.validation.FieldValidationTestHelper.validate;
import static org.assertj.core.api.Assertions.assertThat;

class InsertMutationFieldValidationTest {

    enum Case implements ValidatorCase {

        VALID("insert mutation field — always valid",
            new InsertMutationField("createFilm", null),
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
        assertThat(validate(inQuerySchema("createFilm", tc.field())))
            .extracting(ValidationError::message)
            .containsExactlyInAnyOrderElementsOf(tc.errors());
    }
}
