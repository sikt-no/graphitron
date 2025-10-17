package no.sikt.graphitron.queries.fetch;

import no.sikt.graphitron.common.GeneratorTest;
import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.reducedgenerators.MapOnlyFetchDBClassGenerator;
import no.sikt.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.common.configuration.SchemaComponent.*;

@DisplayName("Query outputs - Row structure and return types")
public class OutputTest extends GeneratorTest {
    @Override
    protected String getSubpath() {
        return "queries/fetch/output";
    }

    @Override
    protected List<ClassGenerator> makeGenerators(ProcessedSchema schema) {
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
                "Map<Row1<Long>, CustomerTable> queryForWrapper",
                "Set<Row1<Long>> wrapperResolverKeys",
                ".select(DSL.row(_a_address.ADDRESS_ID),DSL.field(",
                ".where(DSL.row(_a_address.ADDRESS_ID).in(wrapperResolverKeys))",
                ".fetchMap(r -> r.value1().valuesRow(), Record2::value2"
        );
    }

    @Test
    @DisplayName("Listed return type on non-root level")
    void splitQueryListed() {
        assertGeneratedContentContains(
                "splitQueryListed", Set.of(CUSTOMER_TABLE, SPLIT_QUERY_WRAPPER),
                "Map<Row1<Long>, List<CustomerTable>> queryForWrapper",
                ".select(DSL.row(_a_address.ADDRESS_ID),DSL.multiset(DSL.select",
                ".fetchMap(r -> r.value1().valuesRow(), r -> r.value2().map(Record1::value1))"
        );
    }

    @Test
    @DisplayName("Implicit splitquery due to argument")
    void implicitSplitQuery() {
        assertFilesAreGenerated(
                "implicitSplitQuery", Set.of(CUSTOMER_TABLE, SPLIT_QUERY_WRAPPER),
                "WrapperDBQueries"
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
        assertGeneratedContentContains("fieldOverride", "customer.FIRST_NAME");
    }

    @Test // Not sure if this is allowed. Note, this is actually invalid in our integration tests, since the IDs are jOOQ fields instead of get methods.
    @DisplayName("Containing ID field annotated with @field and has no @nodeId")
    void fieldOverrideID() {
        assertGeneratedContentContains("fieldOverrideID", "payment.getCustomerId()");
    }

    @Test
    @DisplayName("Field annotated with @externalField should use method extended on field's jooq table")
    void externalField() {
        assertGeneratedContentContains("externalField",
                ".select(DSL.row(no.sikt.graphitron.codereferences.extensionmethods.ClassWithExtensionMethod.name(_a_customer))"
        );
    }

    @Test
    @DisplayName("Field annotated with @externalField should map types correctly even when over 22 fields")
    void externalFieldOver22() {
        assertGeneratedContentContains("externalFieldOver22",
                ".select(DSL.row(no.sikt.graphitron.codereferences.extensionmethods.ClassWithExtensionMethod.name(_a_customer),",
                "no.sikt.graphitron.codereferences.extensionmethods.ClassWithExtensionMethod.name(_a_customer).getDataType().convert(r[0]),"
        );
    }

    @Test
    @DisplayName("Returning string without type wrapping")
    void noWrapping() {
        assertGeneratedContentContains(
                "noWrapping", Set.of(CUSTOMER_INPUT_TABLE),
                "String queryForQuery",
                ".select(_a_customer.FIRST_NAME)",
                ".fetchOne(it -> it.into(String.class));"
        );
    }

    @Test
    @DisplayName("Returning listed string without type wrapping")
    void noWrappingListed() {
        assertGeneratedContentContains(
                "noWrappingListed", Set.of(CUSTOMER_INPUT_TABLE),
                "List<String> queryForQuery",
                ".fetch(it -> it.into(String.class));"
        );
    }

    @Test
    @DisplayName("Query fetching two fields")
    void multipleFields() {
        assertGeneratedContentContains("multipleFields", ".row(_a_customer.getId(),_a_customer.EMAIL)");
    }

    @Test // TODO: Should result in an error.
    @DisplayName("Containing field annotated with incorrect @field")
    void invalidFieldOverride() {
        assertGeneratedContentContains("invalidFieldOverride", "customer.WRONG");
    }

    @Test
    @DisplayName("Type without a table on root level wrapping one that has table set")
    void outerNestedRow() {
        assertGeneratedContentContains(
                "outerNestedRow", Set.of(CUSTOMER_TABLE),
                ".row(DSL.field(DSL.select(DSL.row(_a_customer.getId())" +
                        ".mapping(Functions.nullOnAllNull(CustomerTable::new)))" +
                        ".from(_a_customer)))" +
                        ".mapping(Functions.nullOnAllNull(Wrapper::new)))" +
                        ".fetchOne(it -> it.into(Wrapper.class))"
        );
    }

    @Test
    @DisplayName("Type without a table on root level wrapping a listed one that has table set")
    void outerNestedListedRow() {
        assertGeneratedContentContains(
                "outerNestedListedRow", Set.of(CUSTOMER_TABLE),
                ".row(DSL.row(DSL.multiset(DSL.select(DSL.row(_a_customer.getId()).mapping(Functions.nullOnAllNull(CustomerTable::new)))" +
                        ".from(_a_customer)" +
                        ".orderBy(_a_customer.fields(_a_customer.getPrimaryKey().getFieldsArray()))))" +
                        ".mapping(a0 -> a0.map(Record1::value1)))" +
                        ".mapping(Functions.nullOnAllNull((internal_it_) -> new Wrapper(internal_it_))))" +
                        ".fetchOne(it -> it.into(Wrapper.class))"
        );
    }

    @Test  // TODO: Should result in an error. Email has no source.
    @DisplayName("Type without a table on root level wrapping one that has table set with an extra intermediate field")
    void outerNestedRowExtraField() {
        assertGeneratedContentContains(
                "outerNestedRowExtraField", Set.of(CUSTOMER_TABLE),
                ".row(.EMAIL,DSL.field(DSL.select(DSL.row(_a_customer.getId())"
        );
    }

    @Test
    @DisplayName("Two types without a table on root level wrapping one that has table set")
    void outerDoubleNestedRow() {
        assertGeneratedContentContains(
                "outerDoubleNestedRow", Set.of(CUSTOMER_TABLE),
                ".row(DSL.row(DSL.field(DSL.select(DSL.row(_a_customer.getId()).mapping(Functions.nullOnAllNull(CustomerTable::new)))" +
                        ".from(_a_customer)))" +
                        ".mapping(Functions.nullOnAllNull(Wrapper2::new)))" +
                        ".mapping(Functions.nullOnAllNull(Wrapper1::new)))" +
                        ".fetchOne(it -> it.into(Wrapper1.class));"
        );
    }

    @Test
    @DisplayName("Nested output with an argument")
    void outerNestedWithArgument() {
        assertGeneratedContentContains(
                "outerNestedWithArgument", Set.of(CUSTOMER_TABLE),
                "ctx, String firstName,",
                "CustomerTable::new))).from(_a_customer)" +
                        ".where(firstName != null ? _a_customer.FIRST_NAME.eq(firstName) : DSL.noCondition())",
                "Outer::new))).fetchOne" // Checks that no outer where statement exists.
        );
    }

    @Test
    @DisplayName("Nested output with a listed argument")
    void outerNestedWithArgumentListed() {
        assertGeneratedContentContains(
                "outerNestedWithArgumentListed", Set.of(CUSTOMER_TABLE),
                "ctx, List<String> firstName,",
                "CustomerTable::new))).from(_a_customer)" +
                        ".where(firstName.size() > 0 ? _a_customer.FIRST_NAME.in(firstName) : DSL.noCondition())" +
                        ".orderBy(_a_customer.fields(_a_customer.getPrimaryKey().getFieldsArray()))",
                "new Outer(internal_it_)))).fetchOne"
        );
    }

    @Test
    @DisplayName("Double nested output with an argument")
    void outerDoubleNestedWithArgument() {
        assertGeneratedContentContains(
                "outerDoubleNestedWithArgument", Set.of(CUSTOMER_TABLE),
                "ctx, String firstName,",
                "CustomerTable::new))).from(_a_customer)" +
                        ".where(firstName != null ? _a_customer.FIRST_NAME.eq(firstName) : DSL.noCondition())",
                "Wrapper1::new))).fetchOne"
        );
    }

    @Test
    @DisplayName("Type without a table on root level wrapping a list of errors")
    void outerNestedWithListedError() {
        assertGeneratedContentContains(
                "outerNestedWithListedError", Set.of(CUSTOMER_TABLE, VALIDATION_ERROR),
                ".mapping(Functions.nullOnAllNull((internal_it_) -> new Wrapper(internal_it_)"
        );
    }

    @Test
    @DisplayName("Type without a table inside one that has table set")
    void innerNestedRow() {
        assertGeneratedContentContains(
                "innerNestedRow",
                ".row(DSL.row(_a_customer.getId()).mapping(Functions.nullOnAllNull(Wrapper::new))).mapping(Functions.nullOnAllNull(Customer::new"
        );
    }

    @Test
    @DisplayName("Type without a table inside one that has table set with an extra intermediate field")
    void innerNestedRowExtraField() {
        assertGeneratedContentContains(
                "innerNestedRowExtraField",
                ".row(_a_customer.EMAIL,DSL.row(_a_customer.getId()).mapping(Functions.nullOnAllNull(Wrapper::new"
        );
    }

    @Test
    @DisplayName("Two types without a table inside one that has table set")
    void innerDoubleNestedRow() {
        assertGeneratedContentContains(
                "innerDoubleNestedRow",
                ".row(DSL.row(DSL.row(_a_customer.getId())" +
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
                "film.LENGTH",
                "film.RATING.convert",
                "film.LENGTH.getDataType().convert(r[21",
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

    @Test
    @DisplayName("Row with more than 22 fields including key for splitQuery field")
    void over22FieldsWithSplitQuery() {
        assertGeneratedContentContains("over22FieldsWithSplitQuery",
                "new Film( (Record1<Long>) r[0], _a_film.TITLE.getDataType().convert(r[1])",
                "r[22]))"
        );
    }

    @Test // Enhanced null check treats empty objects as null.
    @DisplayName("Required row with optional fields")
    void requiredRowWithOptionalFields() {
        assertGeneratedContentContains(
                "requiredRowWithOptionalFields",
                ".row(DSL.row(_a_customer.EMAIL).mapping(Wrapper::new))" +
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
                ".row(DSL.field(DSL.select(DSL.row(_a_customer_",
                ".mapping(Functions.nullOnAllNull(Address::new)))",
                "))).mapping(Functions.nullOnAllNull(Customer::new");
    }

    @Test
    @DisplayName("Type containing a listed table type (multiset subquery)")
    void innerTableListed() {
        assertGeneratedContentContains(
                "innerTableListed",
                ".row(DSL.multiset(DSL.select(DSL.row(_a_customer_",
                ".mapping(Functions.nullOnAllNull(Address::new)))",
                "))).mapping(Functions.nullOnAllNull(Customer::new");
    }

    @Test
    @DisplayName("Type with a query that references itself")
    void innerTableSelfReference() {
        assertGeneratedContentContains(
                "innerTableSelfReference",
                ".row(_a_film.FILM_ID),DSL.field(",
                ".mapping(Functions.nullOnAllNull(Film::new");
    }

    @Test
    @DisplayName("Simple table type return from multiple schemas")
    void typesFromMultipleSchemas() {
        assertGeneratedContentContains("fromMultipleSchemas",
                Set.of(CUSTOMER_TABLE),
                "fromSchema1ForQuery",
                "fromSchema2ForQuery",
                "pguser.getId()",
                ".from(_a_pguser)");
    }

    @Test
    @DisplayName("Type implementing a table with a subtype collecting some of the table's fields.")
    void subtype() {
        assertGeneratedContentContains(
                "subtype",
                ".getId(),DSL.row(_a_customer.FIRST_NAME,",
                ").mapping(Functions.nullOnAllNull(CustomerName::new))).mapping(Functions.nullOnAllNull(Customer::new))");
    }
}
