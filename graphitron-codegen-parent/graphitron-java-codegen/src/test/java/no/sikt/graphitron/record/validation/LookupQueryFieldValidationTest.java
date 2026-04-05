package no.sikt.graphitron.record.validation;

import no.sikt.graphitron.record.ValidationError;
import no.sikt.graphitron.record.field.ArgumentSpec;
import no.sikt.graphitron.record.field.GraphitronField;
import no.sikt.graphitron.record.field.QueryField.LookupQueryField;
import no.sikt.graphitron.record.field.FieldWrapper;
import no.sikt.graphitron.record.field.ReturnTypeRef;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static no.sikt.graphitron.record.validation.FieldValidationTestHelper.validate;
import static org.assertj.core.api.Assertions.assertThat;

class LookupQueryFieldValidationTest {

    private static LookupQueryField singleReturn(List<ArgumentSpec> arguments) {
        return new LookupQueryField("Query", "filmById", null,
            new ReturnTypeRef.OtherReturnType("Film", new FieldWrapper.Single(true)), arguments);
    }

    enum Case implements ValidatorCase {

        VALID("single return type, no forbidden arg directives — valid",
            singleReturn(List.of()),
            List.of()),

        VALID_WITH_ARGS("single return with plain args — valid",
            singleReturn(List.of(new ArgumentSpec("id", "ID", false, true, false, false))),
            List.of()),

        LIST_RETURN("list cardinality — lookup must return a single object",
            new LookupQueryField("Query", "filmById", null,
                new ReturnTypeRef.OtherReturnType("Film", new FieldWrapper.List(true, true, null, List.of())),
                List.of()),
            List.of("Field 'filmById': lookup fields must return a single object, not a list or connection")),

        CONNECTION_RETURN("connection cardinality — lookup must return a single object",
            new LookupQueryField("Query", "filmById", null,
                new ReturnTypeRef.OtherReturnType("Film", new FieldWrapper.Connection(true, true, null, List.of())),
                List.of()),
            List.of("Field 'filmById': lookup fields must return a single object, not a list or connection")),

        ORDERBY_ARG("@orderBy on a lookup field argument — not valid on lookup",
            singleReturn(List.of(new ArgumentSpec("order", "String", false, false, true, false))),
            List.of("Field 'filmById': @orderBy is not valid on a lookup field")),

        CONDITION_ARG("@condition on a lookup field argument — not valid on lookup",
            singleReturn(List.of(new ArgumentSpec("filter", "String", false, false, false, true))),
            List.of("Field 'filmById': @condition is not valid on a lookup field")),

        ORDERBY_AND_CONDITION_ARGS("both @orderBy and @condition on a lookup field — two errors",
            singleReturn(List.of(
                new ArgumentSpec("order", "String", false, false, true, false),
                new ArgumentSpec("filter", "String", false, false, false, true))),
            List.of(
                "Field 'filmById': @orderBy is not valid on a lookup field",
                "Field 'filmById': @condition is not valid on a lookup field"));

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
    void lookupQueryFieldValidation(Case tc) {
        assertThat(validate(tc.field()))
            .extracting(ValidationError::message)
            .containsExactlyInAnyOrderElementsOf(tc.errors());
    }
}
