package no.sikt.graphitron.rewrite.validation;

import no.sikt.graphitron.rewrite.ValidationError;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.InputField;
import no.sikt.graphitron.rewrite.model.InputField.NestingField;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
import java.util.Optional;

import static no.sikt.graphitron.rewrite.validation.FieldValidationTestHelper.validate;
import static org.assertj.core.api.Assertions.assertThat;

class InputNestingFieldValidationTest {

    enum Case implements ValidatorCase {

        WITH_COLUMN_FIELDS("NestingField wrapping two ColumnFields — no validation errors",
            new NestingField("FooInput", "range", null, "RangeInput", true, false, List.of(
                new InputField.ColumnField("RangeInput", "fromYear", null, "Int", false, false,
                    new ColumnRef("release_year", "RELEASE_YEAR", "java.lang.Integer"), Optional.empty(),
                    new no.sikt.graphitron.rewrite.model.CallSiteExtraction.Direct()),
                new InputField.ColumnField("RangeInput", "toYear", null, "Int", false, false,
                    new ColumnRef("rental_rate", "RENTAL_RATE", "java.math.BigDecimal"), Optional.empty(),
                    new no.sikt.graphitron.rewrite.model.CallSiteExtraction.Direct())
            ), Optional.empty()),
            List.of()),

        NULLABLE("nullable NestingField — no validation errors",
            new NestingField("FooInput", "details", null, "DetailsInput", false, false, List.of(), Optional.empty()),
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
    void inputNestingFieldValidation(Case tc) {
        assertThat(validate(tc.field()))
            .extracting(ValidationError::message)
            .containsExactlyInAnyOrderElementsOf(tc.errors());
    }
}
