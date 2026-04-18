package no.sikt.graphitron.rewrite.validation;

import no.sikt.graphitron.rewrite.ValidationError;
import no.sikt.graphitron.rewrite.model.ChildField.PlatformIdField;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static no.sikt.graphitron.rewrite.validation.FieldValidationTestHelper.validate;
import static org.assertj.core.api.Assertions.assertThat;

class ChildPlatformIdFieldValidationTest {

    enum Case implements ValidatorCase {

        SIMPLE_ID("id field with getId getter — no validation errors",
            new PlatformIdField("Phdreglement", "id", null, "getId"),
            List.of()),

        NON_ID_GETTER("personId field with getPersonId getter — no validation errors",
            new PlatformIdField("Foo", "personId", null, "getPersonId"),
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
    void childPlatformIdFieldValidation(Case tc) {
        assertThat(validate(tc.field()))
            .extracting(ValidationError::message)
            .containsExactlyInAnyOrderElementsOf(tc.errors());
    }
}
