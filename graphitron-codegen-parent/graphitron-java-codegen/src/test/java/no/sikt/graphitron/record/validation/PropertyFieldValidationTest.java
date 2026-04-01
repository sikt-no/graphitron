package no.sikt.graphitron.record.validation;

import no.sikt.graphitron.record.ValidationError;
import no.sikt.graphitron.record.field.GraphitronField;
import no.sikt.graphitron.record.field.PropertyField;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static no.sikt.graphitron.record.validation.FieldValidationTestHelper.inTableTypeSchema;
import static no.sikt.graphitron.record.validation.FieldValidationTestHelper.validate;
import static org.assertj.core.api.Assertions.assertThat;

class PropertyFieldValidationTest {

    enum Case implements ValidatorCase {

        /** No {@code @field} — property name defaults to the GraphQL field name. */
        IMPLICIT_COLUMN {
            public GraphitronField field() {
                return new PropertyField("titleProp", null, "titleProp");
            }
            public List<String> errors() { return List.of(); }
        },

        /** {@code @field(name: "title")} — explicit property name override. */
        EXPLICIT_COLUMN {
            public GraphitronField field() {
                return new PropertyField("titleProp", null, "title");
            }
            public List<String> errors() { return List.of(); }
        };

        public abstract GraphitronField field();
        public abstract List<String> errors();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Case.class)
    void propertyFieldValidation(Case tc) {
        assertThat(validate(inTableTypeSchema("Film", "titleProp", tc.field())))
            .extracting(ValidationError::message)
            .containsExactlyInAnyOrderElementsOf(tc.errors());
    }
}
