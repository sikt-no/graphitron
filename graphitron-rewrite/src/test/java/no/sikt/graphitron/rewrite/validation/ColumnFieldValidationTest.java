package no.sikt.graphitron.rewrite.validation;

import no.sikt.graphitron.rewrite.ValidationError;
import no.sikt.graphitron.rewrite.model.ChildField.ColumnField;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static no.sikt.graphitron.rewrite.validation.FieldValidationTestHelper.validate;
import static org.assertj.core.api.Assertions.assertThat;

class ColumnFieldValidationTest {

    enum Case implements ValidatorCase {

        RESOLVED_IMPLICIT("no @field — column name defaults to the GraphQL field name",
            new ColumnField("Film", "title", null, "title", new ColumnRef("TITLE", "", ""), false),
            List.of()),

        RESOLVED_EXPLICIT("@field(name:) overrides the column name",
            new ColumnField("Film", "title", null, "film_title", new ColumnRef("FILM_TITLE", "", ""), false),
            List.of()),

        JAVA_NAME_PRESENT("@field(javaName:) is not supported — validation error",
            new ColumnField("Film", "title", null, "title", new ColumnRef("TITLE", "", ""), true),
            List.of("Field 'title': @field(javaName:) is not supported in record-based output"));

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
    void columnFieldValidation(Case tc) {
        assertThat(validate(tc.field()))
            .extracting(ValidationError::message)
            .containsExactlyInAnyOrderElementsOf(tc.errors());
    }
}
