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

        /** No {@code @field} — column name defaults to the GraphQL field name. */
        IMPLICIT_COLUMN {
            public GraphitronField field() {
                return new ColumnField("title", null, Optional.empty(), Optional.empty());
            }
            public List<String> errors() { return List.of(); }
        },

        /** {@code @field(name: "film_title")} — explicit column name override. */
        EXPLICIT_COLUMN {
            public GraphitronField field() {
                return new ColumnField("title", null, Optional.of("film_title"), Optional.empty());
            }
            public List<String> errors() { return List.of(); }
        },

        /** {@code @field(name: "film_title", javaName: "filmTitle")} — both overrides. */
        EXPLICIT_COLUMN_AND_JAVA_NAME {
            public GraphitronField field() {
                return new ColumnField("title", null, Optional.of("film_title"), Optional.of("filmTitle"));
            }
            public List<String> errors() { return List.of(); }
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
