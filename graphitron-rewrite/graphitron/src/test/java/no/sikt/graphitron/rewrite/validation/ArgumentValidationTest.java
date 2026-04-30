package no.sikt.graphitron.rewrite.validation;

import no.sikt.graphitron.rewrite.ValidationError;
import no.sikt.graphitron.rewrite.model.BodyParam;
import no.sikt.graphitron.rewrite.model.CallParam;
import no.sikt.graphitron.rewrite.model.CallSiteExtraction;
import no.sikt.graphitron.rewrite.model.ChildField.TableField;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.ConditionFilter;
import no.sikt.graphitron.rewrite.model.FieldWrapper;
import no.sikt.graphitron.rewrite.model.GeneratedConditionFilter;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.MethodRef;
import no.sikt.graphitron.rewrite.model.OrderBySpec;
import no.sikt.graphitron.rewrite.model.ReturnTypeRef;
import no.sikt.graphitron.rewrite.model.TableRef;
import no.sikt.graphitron.rewrite.model.WhereFilter;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static no.sikt.graphitron.rewrite.validation.FieldValidationTestHelper.validate;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validates filter entries on SQL-generating fields. The validator does not currently inspect
 * filter contents, so each case produces no filter-specific errors.
 */
class ArgumentValidationTest {

    private static final ReturnTypeRef.TableBoundReturnType FILM_RETURN =
        new ReturnTypeRef.TableBoundReturnType("Film",
            new TableRef("film", "FILM", "Film", List.of()),
            new FieldWrapper.Single(true));

    private static TableField tableField(List<WhereFilter> filters) {
        return new TableField("Film", "actors", null, FILM_RETURN, List.of(), filters, new OrderBySpec.None(), null);
    }

    enum Case implements ValidatorCase {

        NO_FILTERS("no filters",
            tableField(List.of()),
            List.of()),

        WITH_COLUMN_FILTER("GeneratedConditionFilter scalar",
            tableField(List.of(new GeneratedConditionFilter("TestConditions", "actorsCondition",
                new TableRef("film", "FILM", "Film", List.of()),
                List.of(new CallParam("id", new CallSiteExtraction.Direct(), false, "java.lang.Integer")),
                List.of(new BodyParam.Eq("id", new ColumnRef("film_id", "FILM_ID", "java.lang.Integer"),
                    "java.lang.Integer", false, new CallSiteExtraction.Direct()))))),
            List.of()),

        WITH_INPUT_FILTER("table-bound input type arg — skipped (empty filters)",
            tableField(List.of()),
            List.of()),

        WITH_CONDITION_FILTER("ConditionFilter",
            tableField(List.of(new ConditionFilter("com.example.Conditions", "cond", List.of()))),
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
    void filterValidation(Case tc) {
        assertThat(validate(tc.field()))
            .extracting(ValidationError::message)
            .containsExactlyInAnyOrderElementsOf(tc.errors());
    }
}
