package no.sikt.graphitron.rewrite.validation;

import no.sikt.graphitron.rewrite.ValidationError;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.ArgumentRef;
import no.sikt.graphitron.rewrite.model.QueryField.QueryLookupTableField;
import no.sikt.graphitron.rewrite.model.FieldWrapper;
import no.sikt.graphitron.rewrite.model.ReturnTypeRef;
import no.sikt.graphitron.rewrite.model.TableRef;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
import java.util.Optional;

import static no.sikt.graphitron.rewrite.validation.FieldValidationTestHelper.validate;
import static org.assertj.core.api.Assertions.assertThat;

class QueryLookupTableFieldValidationTest {

    private static QueryLookupTableField singleReturn(List<ArgumentRef> arguments) {
        return new QueryLookupTableField("Query", "filmById", null,
            new ReturnTypeRef.TableBoundReturnType("Film", new TableRef("film", "FILM", "Film", Optional.of(List.of())), new FieldWrapper.Single(true)), arguments);
    }

    enum Case implements ValidatorCase {

        VALID("single return type, no forbidden arg directives — valid",
            singleReturn(List.of()),
            List.of()),

        VALID_WITH_COLUMN_ARG("ScalarArg.ColumnArg scalar (no list) — valid with single return",
            singleReturn(List.of(new ArgumentRef.ScalarArg.ColumnArg("id", "ID", false, false, "FILM_ID", null))),
            List.of()),

        VALID_WITH_LIST_COLUMN_ARG("ScalarArg.ColumnArg list — valid with list return",
            new QueryLookupTableField("Query", "filmById", null,
                new ReturnTypeRef.TableBoundReturnType("Film", new TableRef("film", "FILM", "Film", Optional.of(List.of())), new FieldWrapper.List(true, true, null, List.of())),
                List.of(new ArgumentRef.ScalarArg.ColumnArg("id", "ID", false, true, "FILM_ID", null))),
            List.of()),

        VALID_WITH_TABLE_INPUT_TYPE_ARG("table input type arg — valid with single return",
            singleReturn(List.of(new ArgumentRef.InputTypeArg.TableInputTypeArg("key", "FilmKey", false, false))),
            List.of()),

        VALID_WITH_PLAIN_INPUT_TYPE_ARG("plain input type arg — valid (error handled at type level)",
            singleReturn(List.of(new ArgumentRef.InputTypeArg.PlainInputTypeArg("key", "FilmKey", false, false))),
            List.of()),

        LIST_RETURN_NO_LIST_ARG("list return with no list arg — cardinality mismatch",
            new QueryLookupTableField("Query", "filmById", null,
                new ReturnTypeRef.TableBoundReturnType("Film", new TableRef("film", "FILM", "Film", Optional.of(List.of())), new FieldWrapper.List(true, true, null, List.of())),
                List.of()),
            List.of("Field 'filmById': result type does not match input cardinality")),

        SINGLE_RETURN_LIST_ARG("single return with list arg — cardinality mismatch",
            singleReturn(List.of(new ArgumentRef.ScalarArg.ColumnArg("id", "ID", false, true, "FILM_ID", null))),
            List.of("Field 'filmById': result type does not match input cardinality")),

        CONNECTION_RETURN("connection return — never valid on lookup",
            new QueryLookupTableField("Query", "filmById", null,
                new ReturnTypeRef.TableBoundReturnType("Film", new TableRef("film", "FILM", "Film", Optional.of(List.of())), new FieldWrapper.Connection(true, true, null, List.of())),
                List.of()),
            List.of("Field 'filmById': lookup fields must not return a connection")),

        ORDERBY_ARG("@orderBy on a lookup field argument — not valid on lookup",
            singleReturn(List.of(new ArgumentRef.InputTypeArg.OrderByArg("order", "FilmOrder", false, false, "sortField", "direction"))),
            List.of("Field 'filmById': @orderBy is not valid on a lookup field")),

        MULTIPLE_ORDERBY_ARGS("two @orderBy arguments — one error per argument from the loop",
            singleReturn(List.of(
                new ArgumentRef.InputTypeArg.OrderByArg("order1", "FilmOrder", false, false, "sortField", "direction"),
                new ArgumentRef.InputTypeArg.OrderByArg("order2", "FilmOrder", false, false, "sortField", "direction"))),
            List.of(
                "Field 'filmById': @orderBy is not valid on a lookup field",
                "Field 'filmById': @orderBy is not valid on a lookup field")),

        CONNECTION_AND_ORDERBY("connection return AND @orderBy arg — two independent errors from different branches",
            new QueryLookupTableField("Query", "filmById", null,
                new ReturnTypeRef.TableBoundReturnType("Film", new TableRef("film", "FILM", "Film", Optional.of(List.of())), new FieldWrapper.Connection(true, true, null, List.of())),
                List.of(new ArgumentRef.InputTypeArg.OrderByArg("order", "FilmOrder", false, false, "sortField", "direction"))),
            List.of(
                "Field 'filmById': lookup fields must not return a connection",
                "Field 'filmById': @orderBy is not valid on a lookup field"));

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
