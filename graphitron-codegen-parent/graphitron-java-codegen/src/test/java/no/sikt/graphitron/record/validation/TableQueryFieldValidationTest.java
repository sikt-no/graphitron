package no.sikt.graphitron.record.validation;

import no.sikt.graphitron.record.ValidationError;
import no.sikt.graphitron.record.field.GraphitronField;
import no.sikt.graphitron.record.field.TableQueryField;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static no.sikt.graphitron.record.validation.FieldValidationTestHelper.fieldOf;
import static no.sikt.graphitron.record.validation.FieldValidationTestHelper.inQuerySchema;
import static no.sikt.graphitron.record.validation.FieldValidationTestHelper.list;
import static no.sikt.graphitron.record.validation.FieldValidationTestHelper.typeRef;
import static no.sikt.graphitron.record.validation.FieldValidationTestHelper.validate;
import static org.assertj.core.api.Assertions.assertThat;

class TableQueryFieldValidationTest {

    enum Case implements ValidatorCase {

        VALID {
            public GraphitronField field() {
                return new TableQueryField(fieldOf("films", list(typeRef("Film"))));
            }
            public List<String> errors() { return List.of(); }
        };

        public abstract GraphitronField field();
        public abstract List<String> errors();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Case.class)
    void tableQueryFieldValidation(Case tc) {
        assertThat(validate(inQuerySchema("films", tc.field())))
            .extracting(ValidationError::message)
            .containsExactlyInAnyOrderElementsOf(tc.errors());
    }
}
