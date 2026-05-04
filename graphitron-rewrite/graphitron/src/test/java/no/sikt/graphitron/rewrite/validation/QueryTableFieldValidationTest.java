package no.sikt.graphitron.rewrite.validation;

import no.sikt.graphitron.rewrite.ValidationError;
import no.sikt.graphitron.rewrite.model.OrderBySpec;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.FieldWrapper;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.PaginationSpec;
import no.sikt.graphitron.rewrite.model.ReturnTypeRef;
import no.sikt.graphitron.rewrite.model.QueryField.QueryTableField;
import no.sikt.graphitron.rewrite.model.TableRef;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
import java.util.Optional;

import static no.sikt.graphitron.rewrite.validation.FieldValidationTestHelper.validate;
import static org.assertj.core.api.Assertions.assertThat;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import no.sikt.graphitron.rewrite.TestFixtures;

@UnitTier
class QueryTableFieldValidationTest {

    private static final ColumnRef TITLE_COL = new ColumnRef("title", "TITLE", "java.lang.String");
    private static final ColumnRef ID_COL    = new ColumnRef("film_id", "FILM_ID", "java.lang.Long");
    private static final OrderBySpec.Fixed INDEX_ORDER = new OrderBySpec.Fixed(List.of(new OrderBySpec.ColumnOrderEntry(TITLE_COL, null)), "ASC");
    private static final OrderBySpec.Fixed PK_ORDER    = new OrderBySpec.Fixed(List.of(new OrderBySpec.ColumnOrderEntry(ID_COL, null)), "ASC");

    /** Resolved return type backed by {@code film} (has a primary key). */
    private static ReturnTypeRef.TableBoundReturnType filmReturn(FieldWrapper wrapper) {
        return new ReturnTypeRef.TableBoundReturnType("Film", TestFixtures.tableRef("film", "FILM", "Film", List.of()), wrapper);
    }

    enum Case implements ValidatorCase {

        VALID("no ordering directives — always valid",
            new QueryTableField("Query", "films", null, filmReturn(new FieldWrapper.Single(true)), List.of(), new OrderBySpec.None(), null),
            List.of()),

        DEFAULT_ORDER_INDEX("@defaultOrder with index mode — valid",
            new QueryTableField("Query", "films", null,
                filmReturn(new FieldWrapper.List(true, true)),
                List.of(), INDEX_ORDER, null),
            List.of()),

        DEFAULT_ORDER_PRIMARY_KEY("@defaultOrder with primaryKey mode — valid",
            new QueryTableField("Query", "films", null,
                filmReturn(new FieldWrapper.List(true, true)),
                List.of(), new OrderBySpec.Fixed(List.of(new OrderBySpec.ColumnOrderEntry(ID_COL, null)), "DESC"), null),
            List.of()),

        DEFAULT_ORDER_FIELDS("@defaultOrder with explicit fields — valid",
            new QueryTableField("Query", "films", null,
                filmReturn(new FieldWrapper.List(true, true)),
                List.of(),
                new OrderBySpec.Fixed(List.of(
                    new OrderBySpec.ColumnOrderEntry(TITLE_COL, null),
                    new OrderBySpec.ColumnOrderEntry(ID_COL, "C")),
                    "ASC"),
                null),
            List.of()),

        ORDER_BY_INDEX("@orderBy argument with resolved named orders — valid",
            new QueryTableField("Query", "films", null,
                filmReturn(new FieldWrapper.List(true, true)),
                List.of(),
                new OrderBySpec.Argument("order", "FilmOrder", false, false, "sortField", "directionField",
                    List.of(
                        new OrderBySpec.NamedOrder("TITLE", INDEX_ORDER),
                        new OrderBySpec.NamedOrder("ID", PK_ORDER)),
                    PK_ORDER),
                null),
            List.of()),

        DEFAULT_ORDER_AND_ORDER_BY("@defaultOrder combined with @orderBy argument — valid",
            new QueryTableField("Query", "films", null,
                filmReturn(new FieldWrapper.List(true, true)),
                List.of(),
                new OrderBySpec.Argument("order", "FilmOrder", false, false, "sortField", "directionField",
                    List.of(
                        new OrderBySpec.NamedOrder("TITLE", INDEX_ORDER),
                        new OrderBySpec.NamedOrder("ID", PK_ORDER)),
                    INDEX_ORDER),
                null),
            List.of()),

        PAGINATED_WITH_ORDERING("connection with pagination and ordering — valid",
            new QueryTableField("Query", "films", null,
                filmReturn(new FieldWrapper.Connection(true, 100)),
                List.of(), PK_ORDER,
                new PaginationSpec(
                    new PaginationSpec.PaginationArg("Int", false),
                    null,
                    new PaginationSpec.PaginationArg("String", false),
                    null)),
            List.of()),

        PAGINATED_WITHOUT_ORDERING("connection with pagination but no ordering — error",
            new QueryTableField("Query", "films", null,
                filmReturn(new FieldWrapper.Connection(true, 100)),
                List.of(), new OrderBySpec.None(),
                new PaginationSpec(
                    new PaginationSpec.PaginationArg("Int", false),
                    null,
                    new PaginationSpec.PaginationArg("String", false),
                    null)),
            List.of("Field 'Query.films': paginated fields must have ordering (add @defaultOrder or @orderBy)"));

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
