package no.sikt.graphitron.record.validation;

import no.sikt.graphitron.record.ValidationError;
import no.sikt.graphitron.record.field.DefaultOrderSpec;
import no.sikt.graphitron.record.field.FieldCardinality;
import no.sikt.graphitron.record.field.GraphitronField;
import no.sikt.graphitron.record.field.OrderByEnumValueSpec;
import no.sikt.graphitron.record.field.OrderSpec;
import no.sikt.graphitron.record.field.ReturnTypeRef;
import no.sikt.graphitron.record.field.QueryField.TableQueryField;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static no.sikt.graphitron.jooq.generated.testdata.public_.Tables.FILM;
import static no.sikt.graphitron.jooq.generated.testdata.public_.Tables.FILM_LIST;
import static no.sikt.graphitron.record.validation.FieldValidationTestHelper.validate;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Non-deterministic ordering validation for {@link TableQueryField}.
 *
 * <p>A list or connection field backed by a PK-less table and carrying no {@code @defaultOrder}
 * and no {@code @orderBy} enum is an ordering risk. The validator emits an error so the
 * developer adds explicit ordering or acknowledges the risk.
 */
class TableQueryFieldNonDeterministicOrderingValidationTest {

    /** Resolved return type backed by {@code film_list} (a view — no primary key). */
    private static final ReturnTypeRef FILM_LIST_RETURN = new ReturnTypeRef.ResolvedReturnType("FilmList", FILM_LIST);

    /** Resolved return type backed by {@code film} (has a primary key). */
    private static final ReturnTypeRef FILM_RETURN = new ReturnTypeRef.ResolvedReturnType("Film", FILM);

    enum Case implements ValidatorCase {

        PKLESS_TABLE_NO_ORDER(
            "list field on PK-less table with no @defaultOrder and no @orderBy — non-deterministic error",
            new TableQueryField("Query", "films", null, FILM_LIST_RETURN,
                new FieldCardinality.List(null, List.of())),
            List.of("Field 'films': table 'film_list' has no @defaultOrder directive and no primary key — result ordering is non-deterministic")),

        CONNECTION_PKLESS_TABLE_NO_ORDER(
            "connection field on PK-less table with no @defaultOrder and no @orderBy — non-deterministic error",
            new TableQueryField("Query", "films", null, FILM_LIST_RETURN,
                new FieldCardinality.Connection(null, List.of())),
            List.of("Field 'films': table 'film_list' has no @defaultOrder directive and no primary key — result ordering is non-deterministic")),

        PKLESS_TABLE_WITH_DEFAULT_ORDER(
            "list field on PK-less table with @defaultOrder — ordering guaranteed, no error",
            new TableQueryField("Query", "films", null, FILM_LIST_RETURN,
                new FieldCardinality.List(
                    new DefaultOrderSpec(new OrderSpec.FieldsOrder(List.of()), "ASC"),
                    List.of())),
            List.of()),

        PKLESS_TABLE_WITH_ORDER_BY(
            "list field on PK-less table with @orderBy enum values — ordering configurable, no error",
            new TableQueryField("Query", "films", null, FILM_LIST_RETURN,
                new FieldCardinality.List(null,
                    List.of(new OrderByEnumValueSpec("TITLE", new OrderSpec.FieldsOrder(List.of()))))),
            List.of()),

        TABLE_WITH_PK_NO_ORDER(
            "list field on table with primary key, no @defaultOrder — PK provides determinism, no error",
            new TableQueryField("Query", "films", null, FILM_RETURN,
                new FieldCardinality.List(null, List.of())),
            List.of()),

        SINGLE_CARDINALITY(
            "single cardinality field on PK-less table — ordering irrelevant, no error",
            new TableQueryField("Query", "film", null, FILM_LIST_RETURN,
                new FieldCardinality.Single()),
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
    void nonDeterministicOrderingValidation(Case tc) {
        assertThat(validate(tc.field()))
            .extracting(ValidationError::message)
            .containsExactlyInAnyOrderElementsOf(tc.errors());
    }
}
