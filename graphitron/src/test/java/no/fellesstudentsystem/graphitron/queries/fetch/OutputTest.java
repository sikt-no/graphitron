package no.fellesstudentsystem.graphitron.queries.fetch;

import no.fellesstudentsystem.graphitron.common.GeneratorTest;
import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationTarget;
import no.fellesstudentsystem.graphitron.generators.abstractions.ClassGenerator;
import no.fellesstudentsystem.graphitron.reducedgenerators.MapOnlyFetchDBClassGenerator;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.fellesstudentsystem.graphitron.common.configuration.SchemaComponent.*;

@DisplayName("Query outputs - Row structure and return types")
public class OutputTest extends GeneratorTest {
    @Override
    protected String getSubpath() {
        return "queries/fetch/output";
    }

    @Override
    protected List<ClassGenerator<? extends GenerationTarget>> makeGenerators(ProcessedSchema schema) {
        return List.of(new MapOnlyFetchDBClassGenerator(schema));
    }

    @Test
    @DisplayName("Simple table type return")
    void defaultCase() {
        assertGeneratedContentMatches("default", CUSTOMER_TABLE);
    }

    @Test
    @DisplayName("Listed return type")
    void listed() {
        assertGeneratedContentContains(
                "listed", Set.of(CUSTOMER_TABLE),
                "List<CustomerTable> queryForQuery",
                ".select(DSL.row(",
                ".fetch(it -> it.into(CustomerTable.class"
        );
    }

    @Test
    @DisplayName("Simple table type return on non-root level")
    void splitQuery() {
        assertGeneratedContentContains(
                "splitQuery", Set.of(CUSTOMER_TABLE, SPLIT_QUERY_WRAPPER),
                "Map<String, CustomerTable> queryForWrapper",
                ".select(_customer.getId(),DSL.field(",
                ".fetchMap(Record2::value1, Record2::value2"
        );
    }

    @Test
    @DisplayName("Listed return type on non-root level")
    void splitQueryListed() {
        assertGeneratedContentContains(
                "splitQueryListed", Set.of(CUSTOMER_TABLE, SPLIT_QUERY_WRAPPER),
                "Map<String, List<CustomerTable>> queryForWrapper",
                ".select(_customer.getId(),DSL.multiset(DSL.select",
                ".fetchMap(Record2::value1, r -> r.value2().map(Record1::value1))"
        );
    }

    @Test
    @DisplayName("Containing field annotated with @splitQuery")
    void skipsSplits() {
        resultDoesNotContain("skipsSplits", Set.of(CUSTOMER_TABLE), "CustomerTable::new");
    }

    @Test // TODO: Should result in an error.
    @DisplayName("Containing field annotated with @splitQuery and no other fields")
    void noGeneratedField() {
        assertGeneratedContentContains("noGeneratedField", Set.of(CUSTOMER_TABLE), ".select(.mapping(Functions.nullOnAllNull(Address::new");
    }

    @Test
    @DisplayName("Containing field annotated with @field")
    void fieldOverride() {
        assertGeneratedContentContains("fieldOverride", "_customer.FIRST_NAME");
    }

    @Test
    @DisplayName("Query fetching two fields")
    void multipleFields() {
        assertGeneratedContentContains("multipleFields", ".row(_customer.getId(),_customer.EMAIL)");
    }

    @Test // TODO: Should result in an error.
    @DisplayName("Containing field annotated with incorrect @field")
    void invalidFieldOverride() {
        assertGeneratedContentContains("invalidFieldOverride", "_customer.WRONG");
    }

    @Test
    @DisplayName("Type without a table on root level wrapping one that has table set")
    void outerNestedRow() {
        assertGeneratedContentContains(
                "outerNestedRow", Set.of(CUSTOMER_TABLE),
                ".field(DSL.select(DSL.row(_customer.getId()).mapping(Functions.nullOnAllNull(CustomerTable::new))).from(_customer))).mapping(Functions.nullOnAllNull(Wrapper::new"
        );
    }

    @Test // TODO: Should result in an error.
    @DisplayName("Type without a table on root level wrapping one that has table set with an extra intermediate field")
    void outerNestedRowExtraField() {
        assertGeneratedContentContains(
                "outerNestedRowExtraField", Set.of(CUSTOMER_TABLE),
                ".row(.EMAIL, DSL.field(DSL.select(DSL.row(_customer.getId()).mapping(Functions.nullOnAllNull(CustomerTable::new))).from(_customer)",
                ".from()"
        );
    }

    @Test
    @DisplayName("Two types without a table on root level wrapping one that has table set")
    void outerDoubleNestedRow() {
        assertGeneratedContentContains(
                "outerDoubleNestedRow", Set.of(CUSTOMER_TABLE),
                ".row(DSL.row(DSL.field(DSL.select(DSL.row(_customer.getId())" +
                        ".mapping(Functions.nullOnAllNull(CustomerTable::new)))" +
                        ".from(_customer)))" +
                        ".mapping(Functions.nullOnAllNull(Wrapper2::new)))" +
                        ".mapping(Functions.nullOnAllNull(Wrapper1::new"
        );
    }

    @Test
    @DisplayName("Type without a table inside one that has table set")
    void innerNestedRow() {
        assertGeneratedContentContains(
                "innerNestedRow",
                ".row(DSL.row(_customer.getId()).mapping(Functions.nullOnAllNull(Wrapper::new))).mapping(Functions.nullOnAllNull(Customer::new"
        );
    }

    @Test
    @DisplayName("Type without a table inside one that has table set with an extra intermediate field")
    void innerNestedRowExtraField() {
        assertGeneratedContentContains(
                "innerNestedRowExtraField",
                ".row(_customer.EMAIL,DSL.row(_customer.getId()).mapping(Functions.nullOnAllNull(Wrapper::new"
        );
    }

    @Test
    @DisplayName("Two types without a table inside one that has table set")
    void innerDoubleNestedRow() {
        assertGeneratedContentContains(
                "innerDoubleNestedRow",
                ".row(DSL.row(DSL.row(_customer.getId())" +
                        ".mapping(Functions.nullOnAllNull(Wrapper2::new)))" +
                        ".mapping(Functions.nullOnAllNull(Wrapper1::new)))" +
                        ".mapping(Functions.nullOnAllNull(Customer::new"
        );
    }

    @Test // jOOQ stops type checking at 22 fields.
    @DisplayName("Row with more than 22 fields")
    void over22Fields() {
        assertGeneratedContentMatches("over22Fields");
    }

    @Test
    @DisplayName("Row with more than 22 fields where some have types other than string")
    void over22FieldsWithVariousTypes() {
        assertGeneratedContentContains(
                "over22FieldsWithVariousTypes", Set.of(DUMMY_ENUM),
                "_film.LENGTH",
                "_film.RATING.convert",
                "_film.LENGTH.getDataType().convert(r[21",
                "(DummyEnum) r[22"
        );
    }

    @Test
    @DisplayName("Row with more than 22 fields including an inner row")
    void over22FieldsWithInnerRow() {
        assertGeneratedContentContains("over22FieldsWithInnerRow", "Wrapper::new", "(Wrapper) r[22");
    }

    @Test
    @DisplayName("Row with more than 22 fields including multiset")
    void over22FieldsWithMultiset() {
        assertGeneratedContentContains("over22FieldsWithMultiset", "Wrapper::new", "(List<Wrapper>) r[22]");
    }

    @Test // Enhanced null check treats empty objects as null.
    @DisplayName("Required row with optional fields")
    void requiredRowWithOptionalFields() {
        assertGeneratedContentContains(
                "requiredRowWithOptionalFields",
                ".row(DSL.row(_customer.EMAIL).mapping(Wrapper::new))" +
                        ".mapping((a0) -> (a0 == null || new Wrapper().equals(a0)) ? null : new Customer(a0)"
        );
    }

    @Test
    @DisplayName("Required row with optional field and a neighbour field")
    void requiredRowWithOptionalFieldsAndNeighbourField() {
        assertGeneratedContentContains(
                "requiredRowWithOptionalFieldsAndNeighbourField",
                ".mapping((a0, a1) -> a0 == null && (a1 == null || new Wrapper().equals(a1)) ? null : new Customer(a0, a1"
        );
    }

    @Test
    @DisplayName("Required row with optional field and a neighbour row")
    void requiredRowWithOptionalFieldsAndNeighbourRow() {
        assertGeneratedContentContains(
                "requiredRowWithOptionalFieldsAndNeighbourRow",
                ".mapping(Wrapper::new",
                ".mapping(Functions.nullOnAllNull(Wrapper::new",
                ".mapping((a0, a1) -> (a0 == null || new Wrapper().equals(a0)) && (a1 == null || new Wrapper().equals(a1)) ? null : new Customer(a0, a1"
        );
    }

    @Test
    @DisplayName("Required row with optional field and more than 22 fields")
    void requiredRowWithOptionalFieldsAndOver22Fields() {
        assertGeneratedContentContains(
                "requiredRowWithOptionalFieldsAndOver22Fields",
                ".mapping(Wrapper::new",
                ".mapping(Customer.class, r -> (r[0] == null || new Wrapper().equals(r[0]))" +
                        "&& r[1] == null && r[2] == null && r[3] == null && r[4] == null && r[5] == null && r[6] == null" +
                        "&& r[7] == null && r[8] == null && r[9] == null && r[10] == null && r[11] == null" +
                        "&& r[12] == null && r[13] == null && r[14] == null && r[15] == null && r[16] == null" +
                        "&& r[17] == null && r[18] == null && r[19] == null && r[20] == null && r[21] == null" +
                        "&& r[22] == null && r[23] == null ? null : new Customer("
        );
    }

    @Test
    @DisplayName("Type containing a table type (field subquery)")
    void innerTable() {
        assertGeneratedContentContains(
                "innerTable",
                ".row(DSL.field(DSL.select(DSL.row(customer_",
                ".mapping(Functions.nullOnAllNull(Address::new)))",
                "))).mapping(Functions.nullOnAllNull(Customer::new");
    }

    @Test
    @DisplayName("Type containing a listed table type (multiset subquery)")
    void innerTableListed() {
        assertGeneratedContentContains(
                "innerTableListed",
                ".row(DSL.multiset(DSL.select(DSL.row(customer_",
                ".mapping(Functions.nullOnAllNull(Address::new)))",
                "))).mapping(Functions.nullOnAllNull(Customer::new");
    }

    @Test
    @DisplayName("Type with a query that references itself")
    void innerTableSelfReference() {
        assertGeneratedContentContains(
                "innerTableSelfReference",
                ".getId(),DSL.field(",
                ".mapping(Functions.nullOnAllNull(Film::new");
    }

    @Test
    @DisplayName("Simple table type return from multiple schemas")
    void typesFromMultipleSchemas() {
        assertGeneratedContentContains("fromMultipleSchemas",
                Set.of(CUSTOMER_TABLE),
                "fromSchema1ForQuery",
                "fromSchema2ForQuery",
                "_pguser.getId()",
                ".from(_pguser)");
    }

    @Test
    @DisplayName("Type implementing a table with a subtype collecting some of the table's fields.")
    void subtype() {
        assertGeneratedContentContains(
                "subtype",
                ".getId(),DSL.row(_customer.FIRST_NAME,",
                ").mapping(Functions.nullOnAllNull(CustomerName::new))).mapping(Functions.nullOnAllNull(Customer::new))");
    }
}
