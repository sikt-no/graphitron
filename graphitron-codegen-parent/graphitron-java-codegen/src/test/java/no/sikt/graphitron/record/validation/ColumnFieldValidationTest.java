package no.sikt.graphitron.record.validation;

import no.sikt.graphitron.record.ValidationError;
import no.sikt.graphitron.record.field.ChildField.ColumnField;
import no.sikt.graphitron.record.field.GraphitronField;
import no.sikt.graphitron.record.field.ColumnStep.ResolvedColumn;
import no.sikt.graphitron.record.field.ColumnStep.UnresolvedColumn;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static no.sikt.graphitron.record.validation.FieldValidationTestHelper.inTableTypeSchema;
import static no.sikt.graphitron.record.validation.FieldValidationTestHelper.validate;
import static org.assertj.core.api.Assertions.assertThat;

class ColumnFieldValidationTest {

    enum Case implements ValidatorCase {

        RESOLVED_IMPLICIT("no @field — column name defaults to the GraphQL field name",
            new ColumnField("Film", "title", null, "title", new ResolvedColumn("TITLE", null), false),
            List.of()),

        RESOLVED_EXPLICIT("@field(name:) overrides the column name",
            new ColumnField("Film", "title", null, "film_title", new ResolvedColumn("FILM_TITLE", null), false),
            List.of()),

        UNRESOLVED_COLUMN("column name could not be matched to a jOOQ field in the table",
            new ColumnField("Film", "title", null, "title", new UnresolvedColumn(), false),
            List.of("Field 'title': column 'title' could not be resolved in the jOOQ table")),

        JAVA_NAME_PRESENT("@field(javaName:) is not supported — validation error",
            new ColumnField("Film", "title", null, "title", new ResolvedColumn("TITLE", null), true),
            List.of("Field 'title': @field(javaName:) is not supported in record-based output")),

        JAVA_NAME_WITH_UNRESOLVED_COLUMN("@field(javaName:) + unresolved column — both errors reported",
            new ColumnField("Film", "title", null, "bad_col", new UnresolvedColumn(), true),
            List.of(
                "Field 'title': column 'bad_col' could not be resolved in the jOOQ table",
                "Field 'title': @field(javaName:) is not supported in record-based output"));

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
        assertThat(validate(inTableTypeSchema("Film", "title", tc.field())))
            .extracting(ValidationError::message)
            .containsExactlyInAnyOrderElementsOf(tc.errors());
    }
}
