package no.sikt.graphitron.record.validation;

import no.sikt.graphitron.record.ValidationError;
import no.sikt.graphitron.record.field.DefaultOrderSpec;
import no.sikt.graphitron.record.field.FieldWrapper;
import no.sikt.graphitron.record.field.GraphitronField;
import no.sikt.graphitron.record.field.OrderByEnumValueSpec;
import no.sikt.graphitron.record.field.OrderSpec;
import no.sikt.graphitron.record.field.ReturnTypeRef;
import no.sikt.graphitron.record.field.SortFieldSpec;
import no.sikt.graphitron.record.field.QueryField.TableQueryField;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static no.sikt.graphitron.jooq.generated.testdata.public_.Tables.FILM;
import static no.sikt.graphitron.record.validation.FieldValidationTestHelper.validate;
import static org.assertj.core.api.Assertions.assertThat;

class TableQueryFieldValidationTest {

    /** Resolved return type used for cardinality/ordering cases — {@code film} has a primary key. */
    private static final ReturnTypeRef FILM_RETURN = new ReturnTypeRef.TableBoundReturnType("Film", new no.sikt.graphitron.record.type.TableRef.ResolvedTable("film", "FILM", FILM));

    enum Case implements ValidatorCase {

        VALID("no ordering directives — always valid",
            new TableQueryField("Query", "films", null, FILM_RETURN, new FieldWrapper.Single(true)),
            List.of()),

        UNKNOWN_RETURN_TYPE("return type name does not exist in the schema — validation error",
            new TableQueryField("Query", "films", null, new ReturnTypeRef.UnresolvedReturnType("UnknownType"),
                new FieldWrapper.List(true, true, null, List.of())),
            List.of("Field 'films': return type 'UnknownType' does not exist in the schema")),

        DEFAULT_ORDER_INDEX("@defaultOrder with index mode — valid",
            new TableQueryField("Query", "films", null, FILM_RETURN,
                new FieldWrapper.List(true, true, new DefaultOrderSpec(new OrderSpec.IndexOrder("IDX_TITLE"), "ASC"), List.of())),
            List.of()),

        DEFAULT_ORDER_PRIMARY_KEY("@defaultOrder with primaryKey mode — valid",
            new TableQueryField("Query", "films", null, FILM_RETURN,
                new FieldWrapper.List(true, true, new DefaultOrderSpec(new OrderSpec.PrimaryKeyOrder(), "DESC"), List.of())),
            List.of()),

        DEFAULT_ORDER_FIELDS("@defaultOrder with explicit fields — valid",
            new TableQueryField("Query", "films", null, FILM_RETURN,
                new FieldWrapper.List(true, true, 
                    new DefaultOrderSpec(
                        new OrderSpec.FieldsOrder(List.of(new SortFieldSpec("title", null), new SortFieldSpec("film_id", "C"))),
                        "ASC"),
                    List.of())),
            List.of()),

        ORDER_BY_INDEX("@orderBy argument with @order(index:) enum values — valid",
            new TableQueryField("Query", "films", null, FILM_RETURN,
                new FieldWrapper.List(true, true, null,
                    List.of(
                        new OrderByEnumValueSpec("TITLE", new OrderSpec.IndexOrder("IDX_TITLE")),
                        new OrderByEnumValueSpec("ID", new OrderSpec.PrimaryKeyOrder())))),
            List.of()),

        DEFAULT_ORDER_AND_ORDER_BY("@defaultOrder combined with @orderBy argument — valid",
            new TableQueryField("Query", "films", null, FILM_RETURN,
                new FieldWrapper.List(true, true, 
                    new DefaultOrderSpec(new OrderSpec.IndexOrder("IDX_TITLE"), "ASC"),
                    List.of(
                        new OrderByEnumValueSpec("TITLE", new OrderSpec.IndexOrder("IDX_TITLE")),
                        new OrderByEnumValueSpec("ID", new OrderSpec.PrimaryKeyOrder())))),
            List.of()),

        DEFAULT_ORDER_UNRESOLVED_INDEX("@defaultOrder references an index that could not be found — validation error",
            new TableQueryField("Query", "films", null, FILM_RETURN,
                new FieldWrapper.List(true, true, new DefaultOrderSpec(new OrderSpec.UnresolvedIndexOrder("IDX_MISSING"), "ASC"), List.of())),
            List.of("Field 'films': index 'IDX_MISSING' could not be resolved in the jOOQ catalog")),

        DEFAULT_ORDER_UNRESOLVED_PRIMARY_KEY("@defaultOrder uses primaryKey but the table has none — validation error",
            new TableQueryField("Query", "films", null, FILM_RETURN,
                new FieldWrapper.List(true, true, new DefaultOrderSpec(new OrderSpec.UnresolvedPrimaryKeyOrder(), "DESC"), List.of())),
            List.of("Field 'films': primary key could not be resolved — the table may not have one")),

        ORDER_BY_UNRESOLVED_INDEX("@orderBy enum value references an index that could not be found — validation error",
            new TableQueryField("Query", "films", null, FILM_RETURN,
                new FieldWrapper.List(true, true, null,
                    List.of(new OrderByEnumValueSpec("TITLE", new OrderSpec.UnresolvedIndexOrder("IDX_MISSING"))))),
            List.of("Field 'films': index 'IDX_MISSING' could not be resolved in the jOOQ catalog")),

        ORDER_BY_UNRESOLVED_PRIMARY_KEY("@orderBy enum value uses primaryKey but the table has none — validation error",
            new TableQueryField("Query", "films", null, FILM_RETURN,
                new FieldWrapper.List(true, true, null,
                    List.of(new OrderByEnumValueSpec("ID", new OrderSpec.UnresolvedPrimaryKeyOrder())))),
            List.of("Field 'films': primary key could not be resolved — the table may not have one")),

        ORDER_BY_MULTIPLE_UNRESOLVED("multiple @orderBy enum values with unresolved specs — one error per value",
            new TableQueryField("Query", "films", null, FILM_RETURN,
                new FieldWrapper.List(true, true, null,
                    List.of(
                        new OrderByEnumValueSpec("TITLE", new OrderSpec.UnresolvedIndexOrder("IDX_A")),
                        new OrderByEnumValueSpec("ID", new OrderSpec.UnresolvedPrimaryKeyOrder())))),
            List.of(
                "Field 'films': index 'IDX_A' could not be resolved in the jOOQ catalog",
                "Field 'films': primary key could not be resolved — the table may not have one")),

        CONNECTION_DEFAULT_ORDER_UNRESOLVED_INDEX("connection cardinality: @defaultOrder references an index that could not be found — validation error",
            new TableQueryField("Query", "films", null, FILM_RETURN,
                new FieldWrapper.Connection(true, true, new DefaultOrderSpec(new OrderSpec.UnresolvedIndexOrder("IDX_MISSING"), "ASC"), List.of())),
            List.of("Field 'films': index 'IDX_MISSING' could not be resolved in the jOOQ catalog")),

        CONNECTION_DEFAULT_ORDER_UNRESOLVED_PRIMARY_KEY("connection cardinality: @defaultOrder uses primaryKey but the table has none — validation error",
            new TableQueryField("Query", "films", null, FILM_RETURN,
                new FieldWrapper.Connection(true, true, new DefaultOrderSpec(new OrderSpec.UnresolvedPrimaryKeyOrder(), "DESC"), List.of())),
            List.of("Field 'films': primary key could not be resolved — the table may not have one")),

        CONNECTION_ORDER_BY_UNRESOLVED_INDEX("connection cardinality: @orderBy enum value references an unresolved index — validation error",
            new TableQueryField("Query", "films", null, FILM_RETURN,
                new FieldWrapper.Connection(true, true, null,
                    List.of(new OrderByEnumValueSpec("TITLE", new OrderSpec.UnresolvedIndexOrder("IDX_MISSING"))))),
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
