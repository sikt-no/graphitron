package no.sikt.graphitron.record.validation;

import no.sikt.graphitron.record.ValidationError;
import no.sikt.graphitron.record.field.ColumnReferenceField;
import no.sikt.graphitron.record.field.GraphitronField;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
import java.util.Optional;

import static no.sikt.graphitron.record.validation.FieldValidationTestHelper.inTableTypeSchema;
import static no.sikt.graphitron.record.validation.FieldValidationTestHelper.validate;
import static org.assertj.core.api.Assertions.assertThat;

class ColumnReferenceFieldValidationTest {

    enum Case implements ValidatorCase {

        /** No {@code @field} — column name defaults to the GraphQL field name; column resolved. */
        RESOLVED_IMPLICIT {
            public GraphitronField field() {
                return new ColumnReferenceField("languageName", null, "languageName", "NAME", Optional.empty());
            }
            public List<String> errors() { return List.of(); }
        },

        /** {@code @field(name: "language_name")} — explicit column name override; column resolved. */
        RESOLVED_EXPLICIT {
            public GraphitronField field() {
                return new ColumnReferenceField("languageName", null, "language_name", "NAME", Optional.empty());
            }
            public List<String> errors() { return List.of(); }
        },

        /** Column name could not be matched to a jOOQ field in the joined table. */
        UNRESOLVED_COLUMN {
            public GraphitronField field() {
                return new ColumnReferenceField("languageName", null, "languageName", null, Optional.empty());
            }
            public List<String> errors() {
                return List.of("Field 'languageName': column 'languageName' could not be resolved in the jOOQ table");
            }
        };

        public abstract GraphitronField field();
        public abstract List<String> errors();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Case.class)
    void columnReferenceFieldValidation(Case tc) {
        assertThat(validate(inTableTypeSchema("Film", "languageName", tc.field())))
            .extracting(ValidationError::message)
            .containsExactlyInAnyOrderElementsOf(tc.errors());
    }
}
