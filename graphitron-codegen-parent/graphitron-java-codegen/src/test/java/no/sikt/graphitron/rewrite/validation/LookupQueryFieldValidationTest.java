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

        VALID_WITH_COLUMN_ARG("ScalarArg.ColumnArg scalar (no list) — valid with single return",
            singleReturn(List.of(new ArgumentRef.ScalarArg.ColumnArg("id", "ID", false, false, "FILM_ID", null))),
            List.of()),

        VALID_WITH_LIST_COLUMN_ARG("ScalarArg.ColumnArg list — valid with list return",
            new LookupQueryField("Query", "filmById", null,
                new ReturnTypeRef.OtherReturnType("Film", new FieldWrapper.List(true, true, null, List.of())),
                List.of(new ArgumentRef.ScalarArg.ColumnArg("id", "ID", false, true, "FILM_ID", null))),
            List.of()),

        VALID_WITH_TABLE_INPUT_TYPE_ARG("table input type arg — valid with single return",
            singleReturn(List.of(new ArgumentRef.InputTypeArg.TableInputTypeArg("key", "FilmKey", false, false))),
            List.of()),

        VALID_WITH_PLAIN_INPUT_TYPE_ARG("plain input type arg — valid (error handled at type level)",
            singleReturn(List.of(new ArgumentRef.InputTypeArg.PlainInputTypeArg("key", "FilmKey", false, false))),
            List.of()),

        LIST_RETURN_NO_LIST_ARG("list return with no list arg — cardinality mismatch",
            new LookupQueryField("Query", "filmById", null,
                new ReturnTypeRef.OtherReturnType("Film", new FieldWrapper.List(true, true, null, List.of())),
                List.of()),
            List.of("Field 'filmById': result type does not match input cardinality")),

        SINGLE_RETURN_LIST_ARG("single return with list arg — cardinality mismatch",
            singleReturn(List.of(new ArgumentRef.ScalarArg.ColumnArg("id", "ID", false, true, "FILM_ID", null))),
            List.of("Field 'filmById': result type does not match input cardinality")),

        CONNECTION_RETURN("connection return — never valid on lookup",
            new LookupQueryField("Query", "filmById", null,
                new ReturnTypeRef.OtherReturnType("Film", new FieldWrapper.Connection(true, true, null, List.of())),
                List.of()),
            List.of("Field 'filmById': lookup fields must not return a connection")),

        ORDERBY_ARG("@orderBy on a lookup field argument — not valid on lookup",
            singleReturn(List.of(new ArgumentRef.InputTypeArg.OrderByArg("order", "FilmOrder", false, false, "sortField", "direction"))),
            List.of("Field 'filmById': @orderBy is not valid on a lookup field")),

        UNCLASSIFIED_ARG("UnclassifiedArg — reports reason as error",
            singleReturn(List.of(new ArgumentRef.UnclassifiedArg("filter", "String", false, false,
                "@condition is only supported on field definitions, not on arguments"))),
            List.of("Field 'filmById', argument 'filter': @condition is only supported on field definitions, not on arguments")),

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
