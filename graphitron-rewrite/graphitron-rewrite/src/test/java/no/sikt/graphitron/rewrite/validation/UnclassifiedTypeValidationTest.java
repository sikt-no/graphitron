package no.sikt.graphitron.rewrite.validation;

import no.sikt.graphitron.rewrite.ValidationError;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.GraphitronType.UnclassifiedType;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static no.sikt.graphitron.rewrite.validation.FieldValidationTestHelper.validate;
import static org.assertj.core.api.Assertions.assertThat;

class UnclassifiedTypeValidationTest {

    enum Case implements TypeValidatorCase {

        CONFLICTING_DIRECTIVES("conflicting @table and @record directives",
            new UnclassifiedType("Film", null, "conflicting directives @table and @record"),
            List.of("Type 'Film': conflicting directives @table and @record")),

        UNKNOWN_TABLE("@table name cannot be resolved in the jOOQ catalog",
            new UnclassifiedType("Actor", null, "table 'unknown_table' not found in jOOQ catalog"),
            List.of("Type 'Actor': table 'unknown_table' not found in jOOQ catalog"));

        private final String description;
        private final GraphitronType type;
        private final List<String> errors;

        Case(String description, GraphitronType type, List<String> errors) {
            this.description = description;
            this.type = type;
            this.errors = errors;
        }

        @Override public GraphitronType type() { return type; }
        @Override public List<String> errors() { return errors; }
        @Override public String toString() { return description; }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Case.class)
    void unclassifiedTypeValidation(Case tc) {
        assertThat(validate(tc.type()))
            .extracting(ValidationError::message)
            .containsExactlyInAnyOrderElementsOf(tc.errors());
    }
}
