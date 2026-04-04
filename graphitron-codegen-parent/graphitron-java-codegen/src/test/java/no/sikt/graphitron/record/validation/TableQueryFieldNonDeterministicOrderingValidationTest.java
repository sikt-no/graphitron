package no.sikt.graphitron.record.validation;

import no.sikt.graphitron.record.GraphitronSchema;
import no.sikt.graphitron.record.ValidationError;
import no.sikt.graphitron.record.field.DefaultOrderSpec;
import no.sikt.graphitron.record.field.FieldCardinality;
import no.sikt.graphitron.record.field.OrderByEnumValueSpec;
import no.sikt.graphitron.record.field.OrderSpec;
import no.sikt.graphitron.record.field.QueryField.TableQueryField;
import no.sikt.graphitron.record.type.GraphitronType.TableType;
import no.sikt.graphitron.record.type.NodeRef.NoNode;
import no.sikt.graphitron.record.type.TableRef.ResolvedTable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static no.sikt.graphitron.jooq.generated.testdata.public_.Tables.FILM;
import static no.sikt.graphitron.jooq.generated.testdata.public_.Tables.FILM_LIST;
import static no.sikt.graphitron.record.validation.FieldValidationTestHelper.inQuerySchema;
import static no.sikt.graphitron.record.validation.FieldValidationTestHelper.inQuerySchemaWithReturnType;
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

    /** A {@link TableType} backed by {@code film_list} (a view — no primary key). */
    private static final TableType FILM_LIST_TYPE =
        new TableType("FilmList", null, new ResolvedTable("film_list", "FILM_LIST", FILM_LIST), new NoNode());

    /** A {@link TableType} backed by {@code film} (has a primary key). */
    private static final TableType FILM_TYPE =
        new TableType("Film", null, new ResolvedTable("film", "FILM", FILM), new NoNode());

    enum Case {

        PKLESS_TABLE_NO_ORDER(
            "list field on PK-less table with no @defaultOrder and no @orderBy — non-deterministic error",
            inQuerySchemaWithReturnType("films",
                new TableQueryField("Query", "films", null, "FilmList",
                    new FieldCardinality.List(null, List.of())),
                FILM_LIST_TYPE),
            List.of("Field 'films': table 'film_list' has no @defaultOrder directive and no primary key — result ordering is non-deterministic")),

        CONNECTION_PKLESS_TABLE_NO_ORDER(
            "connection field on PK-less table with no @defaultOrder and no @orderBy — non-deterministic error",
            inQuerySchemaWithReturnType("films",
                new TableQueryField("Query", "films", null, "FilmList",
                    new FieldCardinality.Connection(null, List.of())),
                FILM_LIST_TYPE),
            List.of("Field 'films': table 'film_list' has no @defaultOrder directive and no primary key — result ordering is non-deterministic")),

        PKLESS_TABLE_WITH_DEFAULT_ORDER(
            "list field on PK-less table with @defaultOrder — ordering guaranteed, no error",
            inQuerySchemaWithReturnType("films",
                new TableQueryField("Query", "films", null, "FilmList",
                    new FieldCardinality.List(
                        new DefaultOrderSpec(new OrderSpec.FieldsOrder(List.of()), "ASC"),
                        List.of())),
                FILM_LIST_TYPE),
            List.of()),

        PKLESS_TABLE_WITH_ORDER_BY(
            "list field on PK-less table with @orderBy enum values — ordering configurable, no error",
            inQuerySchemaWithReturnType("films",
                new TableQueryField("Query", "films", null, "FilmList",
                    new FieldCardinality.List(null,
                        List.of(new OrderByEnumValueSpec("TITLE", new OrderSpec.FieldsOrder(List.of()))))),
                FILM_LIST_TYPE),
            List.of()),

        TABLE_WITH_PK_NO_ORDER(
            "list field on table with primary key, no @defaultOrder — PK provides determinism, no error",
            inQuerySchemaWithReturnType("films",
                new TableQueryField("Query", "films", null, "Film",
                    new FieldCardinality.List(null, List.of())),
                FILM_TYPE),
            List.of()),

        SINGLE_CARDINALITY(
            "single cardinality field on PK-less table — ordering irrelevant, no error",
            inQuerySchemaWithReturnType("film",
                new TableQueryField("Query", "film", null, "FilmList",
                    new FieldCardinality.Single()),
                FILM_LIST_TYPE),
            List.of()),

        UNKNOWN_RETURN_TYPE(
            "returnTypeName references a type absent from the schema — broken schema, error",
            inQuerySchema("films",
                new TableQueryField("Query", "films", null, "UnknownType",
                    new FieldCardinality.List(null, List.of()))),
            List.of("Field 'films': return type 'UnknownType' does not exist in the schema"));

        private final String description;
        private final GraphitronSchema schema;
        private final List<String> errors;

        Case(String description, GraphitronSchema schema, List<String> errors) {
            this.description = description;
            this.schema = schema;
            this.errors = errors;
        }

        @Override
        public String toString() { return description; }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Case.class)
    void nonDeterministicOrderingValidation(Case tc) {
        assertThat(validate(tc.schema))
            .extracting(ValidationError::message)
            .containsExactlyInAnyOrderElementsOf(tc.errors);
    }
}
