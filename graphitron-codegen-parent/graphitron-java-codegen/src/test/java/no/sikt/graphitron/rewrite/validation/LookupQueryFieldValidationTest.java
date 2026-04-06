package no.sikt.graphitron.rewrite.validation;

import no.sikt.graphitron.rewrite.ValidationError;
import no.sikt.graphitron.rewrite.field.GraphitronField;
import no.sikt.graphitron.rewrite.field.LookupArgRef;
import no.sikt.graphitron.rewrite.field.QueryField.LookupQueryField;
import no.sikt.graphitron.rewrite.field.FieldWrapper;
import no.sikt.graphitron.rewrite.field.ReturnTypeRef;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static no.sikt.graphitron.rewrite.validation.FieldValidationTestHelper.validate;
import static org.assertj.core.api.Assertions.assertThat;

class LookupQueryFieldValidationTest {

    private static LookupQueryField singleReturn(List<LookupArgRef> arguments) {
        return new LookupQueryField("Query", "filmById", null,
            new ReturnTypeRef.OtherReturnType("Film", new FieldWrapper.Single(true)), arguments);
    }

    enum Case implements ValidatorCase {

        VALID("single return type, no forbidden arg directives — valid",
            singleReturn(List.of()),
            List.of()),

        VALID_WITH_RESOLVED_FLAT_ARG("resolved flat arg — valid",
            singleReturn(List.of(new LookupArgRef.ResolvedFlatArg("id", "ID", false, true, "FILM_ID", null))),
            List.of()),

        VALID_WITH_INPUT_TYPE_ARG("input type arg — valid (error handling deferred to type validator)",
            singleReturn(List.of(new LookupArgRef.InputTypeArg("key", "FilmKey", false, true))),
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
            singleReturn(List.of(new LookupArgRef.OrderByArg("order", "String", false, false))),
            List.of("Field 'filmById': @orderBy is not valid on a lookup field")),

        CONDITION_ARG("@condition on a lookup field argument — not valid on lookup",
            singleReturn(List.of(new LookupArgRef.ConditionArg("filter", "String", false, false))),
            List.of("Field 'filmById': @condition is not valid on a lookup field")),

        ORDERBY_AND_CONDITION_ARGS("both @orderBy and @condition on a lookup field — two errors",
            singleReturn(List.of(
                new LookupArgRef.OrderByArg("order", "String", false, false),
                new LookupArgRef.ConditionArg("filter", "String", false, false))),
            List.of(
                "Field 'filmById': @orderBy is not valid on a lookup field",
                "Field 'filmById': @condition is not valid on a lookup field")),

        UNRESOLVED_FLAT_ARG("unresolved flat arg — reports column error",
            singleReturn(List.of(new LookupArgRef.UnresolvedFlatArg("tenantId", "String", false, false, "tenant_id"))),
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
