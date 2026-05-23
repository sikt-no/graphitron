package no.sikt.graphitron.rewrite.validation;

import no.sikt.graphitron.rewrite.ValidationError;
import no.sikt.graphitron.rewrite.model.ChildField.TableField;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.FieldWrapper;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.OrderBySpec;
import no.sikt.graphitron.rewrite.model.QueryField.QueryTableField;
import no.sikt.graphitron.rewrite.model.ReturnTypeRef;
import no.sikt.graphitron.rewrite.TestFixtures;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static no.sikt.graphitron.rewrite.validation.FieldValidationTestHelper.validate;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit-tier coverage for the cross-cutting "list fields require deterministic ordering" check
 * in {@code GraphitronSchemaValidator.validateListRequiresOrdering}. The predicate is leaf-agnostic
 * (gates only on {@code SqlGeneratingField + FieldWrapper.List + OrderBySpec.None}), so per-variant
 * repetition would be bookkeeping; one Query-rooted surface and one child-position surface exercise
 * both halves of the {@code validateField} dispatch path.
 */
@UnitTier
class ListRequiresOrderingValidationTest {

    private static final ColumnRef FILM_ID = new ColumnRef("film_id", "FILM_ID", "java.lang.Integer");
    private static final OrderBySpec.Fixed PK_ORDER =
        new OrderBySpec.Fixed(List.of(new OrderBySpec.ColumnOrderEntry(FILM_ID, null)), "ASC");

    private static ReturnTypeRef.TableBoundReturnType filmReturn(FieldWrapper wrapper) {
        return new ReturnTypeRef.TableBoundReturnType(
            "Film", TestFixtures.tableRef("film", "FILM", "Film", List.of()), wrapper);
    }

    private static final String LIST_ORDERING_ERROR =
        "list fields must have a deterministic order. Add a primary key to the target table, or use "
            + "@defaultOrder or @orderBy.";

    enum Case implements ValidatorCase {

        QUERY_LIST_NO_ORDER("Query-rooted list field on no-PK table, no @defaultOrder — error",
            new QueryTableField("Query", "films", null,
                filmReturn(new FieldWrapper.List(true, true)),
                List.of(), new OrderBySpec.None(), null),
            List.of("Field 'Query.films': " + LIST_ORDERING_ERROR)),

        QUERY_LIST_WITH_DEFAULT_ORDER("Query-rooted list field with @defaultOrder resolved to Fixed — admit",
            new QueryTableField("Query", "films", null,
                filmReturn(new FieldWrapper.List(true, true)),
                List.of(), PK_ORDER, null),
            List.of()),

        QUERY_SINGLE_NO_ORDER("Query-rooted single field on no-PK table — admit (non-goal)",
            new QueryTableField("Query", "film", null,
                filmReturn(new FieldWrapper.Single(true)),
                List.of(), new OrderBySpec.None(), null),
            List.of()),

        CHILD_LIST_NO_ORDER("Child-position list TableField on no-PK table — error",
            new TableField("Parent", "films", null,
                filmReturn(new FieldWrapper.List(true, true)),
                List.of(), List.of(), new OrderBySpec.None(), null,
                /* parentCorrelation */ null),
            List.of("Field 'Parent.films': " + LIST_ORDERING_ERROR)),

        CHILD_LIST_WITH_DEFAULT_ORDER("Child-position list TableField with Fixed ordering — admit",
            new TableField("Parent", "films", null,
                filmReturn(new FieldWrapper.List(true, true)),
                List.of(), List.of(), PK_ORDER, null,
                /* parentCorrelation */ null),
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
    void listRequiresOrderingValidation(Case tc) {
        assertThat(validate(tc.field()))
            .extracting(ValidationError::message)
            .containsExactlyInAnyOrderElementsOf(tc.errors());
    }
}
