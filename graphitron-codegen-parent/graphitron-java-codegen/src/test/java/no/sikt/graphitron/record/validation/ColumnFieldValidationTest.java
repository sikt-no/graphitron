package no.sikt.graphitron.record.validation;

import no.sikt.graphitron.record.ValidationError;
import no.sikt.graphitron.record.field.ColumnField;
import no.sikt.graphitron.record.field.GraphitronField;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
import java.util.Optional;

import static no.sikt.graphitron.record.validation.FieldValidationTestHelper.inTableTypeSchema;
import static no.sikt.graphitron.record.validation.FieldValidationTestHelper.validate;
import static org.assertj.core.api.Assertions.assertThat;

class ColumnFieldValidationTest {

    enum Case implements ValidatorCase {

        /** No {@code @field} — column name defaults to the GraphQL field name; column resolved. */
        RESOLVED_IMPLICIT {
            public GraphitronField field() {
                return new ColumnField("title", null, "title", "TITLE", Optional.empty());
            }
            public List<String> errors() { return List.of(); }
        },

        /** {@code @field(name: "film_title")} — explicit column name override; column resolved. */
        RESOLVED_EXPLICIT {
            public GraphitronField field() {
                return new ColumnField("title", null, "film_title", "FILM_TITLE", Optional.empty());
            }
            public List<String> errors() { return List.of(); }
        },

        /** Column name could not be matched to a jOOQ field in the table. */
        UNRESOLVED_COLUMN {
            public GraphitronField field() {
                return new ColumnField("title", null, "title", null, Optional.empty());
            }
            public List<String> errors() {
                return List.of("Field 'title': column 'title' could not be resolved in the jOOQ table");
            }
        };

        public abstract GraphitronField field();
        public abstract List<String> errors();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Case.class)
    void columnFieldValidation(Case tc) {
        assertThat(validate(inTableTypeSchema("Film", "title", tc.field())))
            .extracting(ValidationError::message)
            .containsExactlyInAnyOrderElementsOf(tc.errors());
    }
}
