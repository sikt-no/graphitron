package no.sikt.graphitron.rewrite.validation;

import no.sikt.graphitron.rewrite.ValidationError;
import no.sikt.graphitron.rewrite.model.BodyParam;
import no.sikt.graphitron.rewrite.model.CallParam;
import no.sikt.graphitron.rewrite.model.CallSiteExtraction;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.FieldWrapper;
import no.sikt.graphitron.rewrite.model.GeneratedConditionFilter;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.OrderBySpec;
import no.sikt.graphitron.rewrite.model.QueryField.QueryLookupTableField;
import no.sikt.graphitron.rewrite.model.ReturnTypeRef;
import no.sikt.graphitron.rewrite.model.TableRef;
import no.sikt.graphitron.rewrite.model.WhereFilter;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static no.sikt.graphitron.rewrite.validation.FieldValidationTestHelper.validate;
import static org.assertj.core.api.Assertions.assertThat;

class QueryLookupTableFieldValidationTest {

    private static final ColumnRef FILM_ID_COL = new ColumnRef("film_id", "FILM_ID", "java.lang.Integer");
    private static final TableRef FILM_TABLE = new TableRef("film", "FILM", "Film", List.of());

    private static GeneratedConditionFilter columnFilter(String name, boolean nonNull, boolean list) {
        var bodyParam = new BodyParam(name, FILM_ID_COL, "java.lang.Integer", nonNull, list, new CallSiteExtraction.Direct());
        var callParam = new CallParam(name, new CallSiteExtraction.Direct());
        return new GeneratedConditionFilter("TestConditions", "testCondition", FILM_TABLE,
            List.of(callParam), List.of(bodyParam));
    }

    private static QueryLookupTableField singleReturn(List<WhereFilter> filters, OrderBySpec orderBy) {
        return new QueryLookupTableField("Query", "filmById", null,
            new ReturnTypeRef.TableBoundReturnType("Film", new TableRef("film", "FILM", "Film", List.of()), new FieldWrapper.Single(true)),
            filters, orderBy, null);
    }

    enum Case implements ValidatorCase {

        VALID("single return type, no filters — valid",
            singleReturn(List.of(), new OrderBySpec.None()),
            List.of()),

        VALID_WITH_COLUMN_ARG("GeneratedConditionFilter scalar (no list) — valid with single return",
            singleReturn(List.of(columnFilter("id", false, false)), new OrderBySpec.None()),
            List.of()),

        VALID_WITH_LIST_COLUMN_ARG("GeneratedConditionFilter list — valid with list return",
            new QueryLookupTableField("Query", "filmById", null,
                new ReturnTypeRef.TableBoundReturnType("Film", FILM_TABLE, new FieldWrapper.List(true, true)),
                List.of(columnFilter("id", false, true)), new OrderBySpec.None(), null),
            List.of()),

        VALID_WITH_TABLE_INPUT_TYPE_ARG("table-bound input type arg — skipped, empty filters, valid with single return",
            singleReturn(List.of(), new OrderBySpec.None()),
            List.of()),

        LIST_RETURN_NO_LIST_ARG("list return with no list filter — cardinality mismatch",
            new QueryLookupTableField("Query", "filmById", null,
                new ReturnTypeRef.TableBoundReturnType("Film", new TableRef("film", "FILM", "Film", List.of()), new FieldWrapper.List(true, true)),
                List.of(), new OrderBySpec.None(), null),
            List.of("Field 'filmById': result type does not match input cardinality")),

        SINGLE_RETURN_LIST_ARG("single return with list filter — cardinality mismatch",
            singleReturn(List.of(columnFilter("id", false, true)), new OrderBySpec.None()),
            List.of("Field 'filmById': result type does not match input cardinality")),

        CONNECTION_RETURN("connection return — never valid on lookup",
            new QueryLookupTableField("Query", "filmById", null,
                new ReturnTypeRef.TableBoundReturnType("Film", new TableRef("film", "FILM", "Film", List.of()), new FieldWrapper.Connection(true, true)),
                List.of(), new OrderBySpec.None(), null),
            List.of("Field 'filmById': lookup fields must not return a connection")),

        ORDERBY_ARG("@orderBy on a lookup field — not valid on lookup",
            singleReturn(List.of(), new OrderBySpec.Argument("order", "FilmOrder", false, false, "sortField", "direction", List.of(), null)),
            List.of("Field 'filmById': @orderBy is not valid on a lookup field")),

        CONNECTION_AND_ORDERBY("connection return AND @orderBy — two independent errors",
            new QueryLookupTableField("Query", "filmById", null,
                new ReturnTypeRef.TableBoundReturnType("Film", new TableRef("film", "FILM", "Film", List.of()), new FieldWrapper.Connection(true, true)),
                List.of(), new OrderBySpec.Argument("order", "FilmOrder", false, false, "sortField", "direction", List.of(), null), null),
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
