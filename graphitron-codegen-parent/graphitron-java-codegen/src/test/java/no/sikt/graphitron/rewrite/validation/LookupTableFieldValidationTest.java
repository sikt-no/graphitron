package no.sikt.graphitron.rewrite.validation;

import no.sikt.graphitron.rewrite.ValidationError;
import no.sikt.graphitron.rewrite.field.ChildField.LookupTableField;
import no.sikt.graphitron.rewrite.field.FieldWrapper;
import no.sikt.graphitron.rewrite.field.GraphitronField;
import no.sikt.graphitron.rewrite.field.ReturnTypeRef;
import no.sikt.graphitron.rewrite.type.TableRef.ResolvedTable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static no.sikt.graphitron.rewrite.validation.FieldValidationTestHelper.validate;
import static org.assertj.core.api.Assertions.assertThat;

class LookupTableFieldValidationTest {

    private static ReturnTypeRef filmReturn(FieldWrapper wrapper) {
        return new ReturnTypeRef.TableBoundReturnType("Film", new ResolvedTable("film", "FILM", "Film", true, List.of()), wrapper);
    }

    enum Case implements ValidatorCase {

        VALID_SINGLE("single return — valid",
            new LookupTableField("Language", "film", null, filmReturn(new FieldWrapper.Single(true)), List.of(), List.of()),
            List.of()),

        VALID_LIST("list return — valid",
            new LookupTableField("Language", "films", null, filmReturn(new FieldWrapper.List(true, true, null, List.of())), List.of(), List.of()),
            List.of()),

        CONNECTION_BLOCKED("connection return — not valid on lookup field",
            new LookupTableField("Language", "films", null, filmReturn(new FieldWrapper.Connection(true, true, null, List.of())), List.of(), List.of()),
            List.of("Field 'films': lookup fields must not return a connection"));

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
    void lookupTableFieldValidation(Case tc) {
        assertThat(validate(tc.field()))
            .extracting(ValidationError::message)
            .containsExactlyInAnyOrderElementsOf(tc.errors());
    }
}
