package no.sikt.graphitron.rewrite.validation;

import no.sikt.graphitron.rewrite.ValidationError;
import no.sikt.graphitron.rewrite.field.GraphitronField;
import no.sikt.graphitron.rewrite.field.ArgumentRef;
import no.sikt.graphitron.rewrite.field.QueryField.LookupQueryField;
import no.sikt.graphitron.rewrite.field.FieldWrapper;
import no.sikt.graphitron.rewrite.field.ReturnTypeRef;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static no.sikt.graphitron.rewrite.validation.FieldValidationTestHelper.validate;
import static org.assertj.core.api.Assertions.assertThat;

class LookupQueryFieldValidationTest {

    private static LookupQueryField singleReturn(List<ArgumentRef> arguments) {
        return new LookupQueryField("Query", "filmById", null,
            new ReturnTypeRef.OtherReturnType("Film", new FieldWrapper.Single(true)), arguments);
    }

    enum Case implements ValidatorCase {

        VALID("single return type, no forbidden arg directives — valid",
            singleReturn(List.of()),
            List.of()),

        VALID_WITH_COLUMN_ARG("ScalarArg.ColumnArg (resolved) — valid",
            singleReturn(List.of(new ArgumentRef.ScalarArg.ColumnArg("id", "ID", false, true, "FILM_ID", null))),
            List.of()),

        VALID_WITH_TABLE_INPUT_TYPE_ARG("table input type arg — valid",
            singleReturn(List.of(new ArgumentRef.InputTypeArg.TableInputTypeArg("key", "FilmKey", false, true))),
            List.of()),

        VALID_WITH_PLAIN_INPUT_TYPE_ARG("plain input type arg — valid (error handled at type level)",
            singleReturn(List.of(new ArgumentRef.InputTypeArg.PlainInputTypeArg("key", "FilmKey", false, true, false, false))),
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
            singleReturn(List.of(new ArgumentRef.InputTypeArg.PlainInputTypeArg("order", "String", false, false, true, false))),
            List.of("Field 'filmById': @orderBy is not valid on a lookup field")),

        CONDITION_ARG("@condition on a lookup field argument — not valid on lookup",
            singleReturn(List.of(new ArgumentRef.InputTypeArg.PlainInputTypeArg("filter", "String", false, false, false, true))),
            List.of("Field 'filmById': @condition is not valid on a lookup field")),

        ORDERBY_AND_CONDITION_ARGS("both @orderBy and @condition on a lookup field — two errors",
            singleReturn(List.of(
                new ArgumentRef.InputTypeArg.PlainInputTypeArg("order", "String", false, false, true, false),
                new ArgumentRef.InputTypeArg.PlainInputTypeArg("filter", "String", false, false, false, true))),
            List.of(
                "Field 'filmById': @orderBy is not valid on a lookup field",
                "Field 'filmById': @condition is not valid on a lookup field")),

        UNBOUND_SCALAR_ARG("ScalarArg.UnboundScalarArg — reports column error",
            singleReturn(List.of(new ArgumentRef.ScalarArg.UnboundScalarArg("tenantId", "String", false, false, "tenant_id"))),
            List.of("Field 'filmById': argument 'tenantId' could not be resolved to column 'tenant_id' on the return type's table"));

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
