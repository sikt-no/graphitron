package no.sikt.graphitron.rewrite.validation;

import no.sikt.graphitron.rewrite.ValidationError;
import no.sikt.graphitron.rewrite.field.DefaultOrderSpec;
import no.sikt.graphitron.rewrite.field.FieldWrapper;
import no.sikt.graphitron.rewrite.field.GraphitronField;
import no.sikt.graphitron.rewrite.field.OrderByEnumValueSpec;
import no.sikt.graphitron.rewrite.field.OrderSpec;
import no.sikt.graphitron.rewrite.field.ReturnTypeRef;
import no.sikt.graphitron.rewrite.field.SortFieldSpec;
import no.sikt.graphitron.rewrite.field.QueryField.TableQueryField;
import no.sikt.graphitron.rewrite.type.TableRef.ResolvedTable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static no.sikt.graphitron.jooq.generated.testdata.public_.Tables.FILM;
import static no.sikt.graphitron.rewrite.validation.FieldValidationTestHelper.validate;
import static org.assertj.core.api.Assertions.assertThat;

class TableQueryFieldValidationTest {

    /** Resolved return type backed by {@code film} (has a primary key). */
    private static ReturnTypeRef filmReturn(FieldWrapper wrapper) {
        return new ReturnTypeRef.TableBoundReturnType("Film", new ResolvedTable("film", "FILM", FILM), wrapper);
    }

    enum Case implements ValidatorCase {

        VALID("no ordering directives — always valid",
            new TableQueryField("Query", "films", null, filmReturn(new FieldWrapper.Single(true)), List.of()),
            List.of()),

        DEFAULT_ORDER_INDEX("@defaultOrder with index mode — valid",
            new TableQueryField("Query", "films", null,
                filmReturn(new FieldWrapper.List(true, true, new DefaultOrderSpec(new OrderSpec.IndexOrder("IDX_TITLE"), "ASC"), List.of())),
                List.of()),
            List.of()),

        DEFAULT_ORDER_PRIMARY_KEY("@defaultOrder with primaryKey mode — valid",
            new TableQueryField("Query", "films", null,
                filmReturn(new FieldWrapper.List(true, true, new DefaultOrderSpec(new OrderSpec.PrimaryKeyOrder(), "DESC"), List.of())),
                List.of()),
            List.of()),

        DEFAULT_ORDER_FIELDS("@defaultOrder with explicit fields — valid",
            new TableQueryField("Query", "films", null,
                filmReturn(new FieldWrapper.List(true, true,
                    new DefaultOrderSpec(
                        new OrderSpec.FieldsOrder(List.of(new SortFieldSpec("title", null), new SortFieldSpec("film_id", "C"))),
                        "ASC"),
                    List.of())),
                List.of()),
            List.of()),

        ORDER_BY_INDEX("@orderBy argument with @order(index:) enum values — valid",
            new TableQueryField("Query", "films", null,
                filmReturn(new FieldWrapper.List(true, true, null,
                    List.of(
                        new OrderByEnumValueSpec("TITLE", new OrderSpec.IndexOrder("IDX_TITLE")),
                        new OrderByEnumValueSpec("ID", new OrderSpec.PrimaryKeyOrder())))),
                List.of()),
            List.of()),

        DEFAULT_ORDER_AND_ORDER_BY("@defaultOrder combined with @orderBy argument — valid",
            new TableQueryField("Query", "films", null,
                filmReturn(new FieldWrapper.List(true, true,
                    new DefaultOrderSpec(new OrderSpec.IndexOrder("IDX_TITLE"), "ASC"),
                    List.of(
                        new OrderByEnumValueSpec("TITLE", new OrderSpec.IndexOrder("IDX_TITLE")),
                        new OrderByEnumValueSpec("ID", new OrderSpec.PrimaryKeyOrder())))),
                List.of()),
            List.of()),

        DEFAULT_ORDER_UNRESOLVED_INDEX("@defaultOrder references an index that could not be found — validation error",
            new TableQueryField("Query", "films", null,
                filmReturn(new FieldWrapper.List(true, true,
                    new DefaultOrderSpec(new OrderSpec.UnresolvedIndexOrder("IDX_MISSING"), "ASC"), List.of())),
                List.of()),
            List.of("Field 'films': index 'IDX_MISSING' could not be resolved in the jOOQ catalog")),

        DEFAULT_ORDER_UNRESOLVED_PRIMARY_KEY("@defaultOrder uses primaryKey but the table has none — validation error",
            new TableQueryField("Query", "films", null,
                filmReturn(new FieldWrapper.List(true, true,
                    new DefaultOrderSpec(new OrderSpec.UnresolvedPrimaryKeyOrder(), "DESC"), List.of())),
                List.of()),
            List.of("Field 'films': primary key could not be resolved — the table may not have one")),

        ORDER_BY_UNRESOLVED_INDEX("@orderBy enum value references an index that could not be found — validation error",
            new TableQueryField("Query", "films", null,
                filmReturn(new FieldWrapper.List(true, true, null,
                    List.of(new OrderByEnumValueSpec("TITLE", new OrderSpec.UnresolvedIndexOrder("IDX_MISSING"))))),
                List.of()),
            List.of("Field 'films': index 'IDX_MISSING' could not be resolved in the jOOQ catalog")),

        ORDER_BY_UNRESOLVED_PRIMARY_KEY("@orderBy enum value uses primaryKey but the table has none — validation error",
            new TableQueryField("Query", "films", null,
                filmReturn(new FieldWrapper.List(true, true, null,
                    List.of(new OrderByEnumValueSpec("ID", new OrderSpec.UnresolvedPrimaryKeyOrder())))),
                List.of()),
            List.of("Field 'films': primary key could not be resolved — the table may not have one")),

        ORDER_BY_MULTIPLE_UNRESOLVED("multiple @orderBy enum values with unresolved specs — one error per value",
            new TableQueryField("Query", "films", null,
                filmReturn(new FieldWrapper.List(true, true, null,
                    List.of(
                        new OrderByEnumValueSpec("TITLE", new OrderSpec.UnresolvedIndexOrder("IDX_A")),
                        new OrderByEnumValueSpec("ID", new OrderSpec.UnresolvedPrimaryKeyOrder())))),
                List.of()),
            List.of(
                "Field 'films': index 'IDX_A' could not be resolved in the jOOQ catalog",
                "Field 'films': primary key could not be resolved — the table may not have one")),

        CONNECTION_DEFAULT_ORDER_UNRESOLVED_INDEX("connection cardinality: @defaultOrder references an index that could not be found — validation error",
            new TableQueryField("Query", "films", null,
                filmReturn(new FieldWrapper.Connection(true, true,
                    new DefaultOrderSpec(new OrderSpec.UnresolvedIndexOrder("IDX_MISSING"), "ASC"), List.of())),
                List.of()),
            List.of("Field 'films': index 'IDX_MISSING' could not be resolved in the jOOQ catalog")),

        CONNECTION_DEFAULT_ORDER_UNRESOLVED_PRIMARY_KEY("connection cardinality: @defaultOrder uses primaryKey but the table has none — validation error",
            new TableQueryField("Query", "films", null,
                filmReturn(new FieldWrapper.Connection(true, true,
                    new DefaultOrderSpec(new OrderSpec.UnresolvedPrimaryKeyOrder(), "DESC"), List.of())),
                List.of()),
            List.of("Field 'films': primary key could not be resolved — the table may not have one")),

        CONNECTION_ORDER_BY_UNRESOLVED_INDEX("connection cardinality: @orderBy enum value references an unresolved index — validation error",
            new TableQueryField("Query", "films", null,
                filmReturn(new FieldWrapper.Connection(true, true, null,
                    List.of(new OrderByEnumValueSpec("TITLE", new OrderSpec.UnresolvedIndexOrder("IDX_MISSING"))))),
                List.of()),
            List.of("Field 'films': index 'IDX_MISSING' could not be resolved in the jOOQ catalog"));

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
