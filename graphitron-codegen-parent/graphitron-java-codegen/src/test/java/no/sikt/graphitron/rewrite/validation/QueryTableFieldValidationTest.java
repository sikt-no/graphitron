package no.sikt.graphitron.rewrite.validation;

import no.sikt.graphitron.rewrite.ValidationError;
import no.sikt.graphitron.rewrite.model.DefaultOrderSpec;
import no.sikt.graphitron.rewrite.model.FieldWrapper;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.OrderByEnumValueSpec;
import no.sikt.graphitron.rewrite.model.OrderSpec;
import no.sikt.graphitron.rewrite.model.ReturnTypeRef;
import no.sikt.graphitron.rewrite.model.SortFieldSpec;
import no.sikt.graphitron.rewrite.model.QueryField.QueryTableField;
import no.sikt.graphitron.rewrite.model.TableRef;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static no.sikt.graphitron.rewrite.validation.FieldValidationTestHelper.validate;
import static org.assertj.core.api.Assertions.assertThat;

class QueryTableFieldValidationTest {

    /** Resolved return type backed by {@code film} (has a primary key). */
    private static ReturnTypeRef.TableBoundReturnType filmReturn(FieldWrapper wrapper) {
        return new ReturnTypeRef.TableBoundReturnType("Film", new TableRef("film", "FILM", "Film", true, List.of(), List.of()), wrapper);
    }

    enum Case implements ValidatorCase {

        VALID("no ordering directives — always valid",
            new QueryTableField("Query", "films", null, filmReturn(new FieldWrapper.Single(true)), List.of()),
            List.of()),

        DEFAULT_ORDER_INDEX("@defaultOrder with index mode — valid",
            new QueryTableField("Query", "films", null,
                filmReturn(new FieldWrapper.List(true, true, new DefaultOrderSpec(new OrderSpec.IndexOrder("IDX_TITLE"), "ASC"), List.of())),
                List.of()),
            List.of()),

        DEFAULT_ORDER_PRIMARY_KEY("@defaultOrder with primaryKey mode — valid",
            new QueryTableField("Query", "films", null,
                filmReturn(new FieldWrapper.List(true, true, new DefaultOrderSpec(new OrderSpec.PrimaryKeyOrder(), "DESC"), List.of())),
                List.of()),
            List.of()),

        DEFAULT_ORDER_FIELDS("@defaultOrder with explicit fields — valid",
            new QueryTableField("Query", "films", null,
                filmReturn(new FieldWrapper.List(true, true,
                    new DefaultOrderSpec(
                        new OrderSpec.FieldsOrder(List.of(new SortFieldSpec("title", null), new SortFieldSpec("film_id", "C"))),
                        "ASC"),
                    List.of())),
                List.of()),
            List.of()),

        ORDER_BY_INDEX("@orderBy argument with @order(index:) enum values — valid",
            new QueryTableField("Query", "films", null,
                filmReturn(new FieldWrapper.List(true, true, null,
                    List.of(
                        new OrderByEnumValueSpec("TITLE", new OrderSpec.IndexOrder("IDX_TITLE")),
                        new OrderByEnumValueSpec("ID", new OrderSpec.PrimaryKeyOrder())))),
                List.of()),
            List.of()),

        DEFAULT_ORDER_AND_ORDER_BY("@defaultOrder combined with @orderBy argument — valid",
            new QueryTableField("Query", "films", null,
                filmReturn(new FieldWrapper.List(true, true,
                    new DefaultOrderSpec(new OrderSpec.IndexOrder("IDX_TITLE"), "ASC"),
                    List.of(
                        new OrderByEnumValueSpec("TITLE", new OrderSpec.IndexOrder("IDX_TITLE")),
                        new OrderByEnumValueSpec("ID", new OrderSpec.PrimaryKeyOrder())))),
                List.of()),
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
    void tableQueryFieldValidation(Case tc) {
        assertThat(validate(tc.field()))
            .extracting(ValidationError::message)
            .containsExactlyInAnyOrderElementsOf(tc.errors());
    }
}
