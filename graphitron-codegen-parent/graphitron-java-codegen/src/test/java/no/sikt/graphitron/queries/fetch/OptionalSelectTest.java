package no.sikt.graphitron.queries.fetch;

import no.sikt.graphitron.common.GeneratorTest;
import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.reducedgenerators.EntityFetchOnlyDBClassGenerator;
import no.sikt.graphitron.reducedgenerators.InterfaceOnlyFetchDBClassGenerator;
import no.sikt.graphitron.reducedgenerators.MapOnlyFetchDBClassGenerator;
import no.sikt.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.common.configuration.SchemaComponent.*;

@DisplayName("Query outputs - With optional select enabled")
public class OptionalSelectTest extends GeneratorTest {
    @Override
    protected String getSubpath() {
        return "queries/fetch/optionalSelect";
    }

    @Override
    protected List<ClassGenerator> makeGenerators(ProcessedSchema schema) {
        return List.of(
                new MapOnlyFetchDBClassGenerator(schema),
                new EntityFetchOnlyDBClassGenerator(schema),
                new InterfaceOnlyFetchDBClassGenerator(schema)
        );
    }

    @BeforeAll
    static void setUp() {
        GeneratorConfig.setUseOptionalSelects(true);
    }

    @AfterAll
    static void tearDown() {
        GeneratorConfig.setUseOptionalSelects(false);
    }

    @Test
    @DisplayName("Regular table column should not be optional, but SelectionSet should still be passed down")
    void tableColumn() {
        assertGeneratedContentContains(
                "tableColumn",
                "queryForQuery_customer(SelectionSet _iv_select)", // Helper method signature accepts SelectionSet
                "row(_a_customer.FIRST_NAME).mapping" // Not wrapped with select.ifRequested
        );
    }

    @Test
    @DisplayName("SelectionSet should be included in helper method signatures")
    void selectionHelpers() {
        assertGeneratedContentContains(
                "selectionHelpers",
                "queryForQuery_customer(SelectionSet _iv_select)", // Helper
                "_1_queryForQuery_customer_address(SelectionSet _iv_select)", // Nested helper
                "ifRequested(\"address\", () ->", // Check path on subquery
                "ifRequested(\"address/city\", () ->" // Check path on nested subquery
        );
    }

    @Test
    @DisplayName("Field references should be wrapped with ifRequested")
    void fieldReference() {
        assertGeneratedContentContains(
                "fieldReference",
                """
                        row(
                            _iv_select.ifRequested("address", () ->
                                DSL.field(
                                    DSL.select(_1_queryForQuery_customer_address(_iv_select))
                                    .from(_a_customer_2168032777_address)
                                )
                            )
                        ).mapping
                """
        );
    }

    @Test
    @DisplayName("Nested field references should have correct path")
    void nestedFieldReference() {
        assertGeneratedContentContains(
                "nestedFieldReference",
                "ifRequested(\"address/cityId\", "
        );
    }

    @Test
    @DisplayName("Multiset references should be wrapped with ifRequested")
    void multisetReference() {
        assertGeneratedContentContains(
                "multisetReference",
                """
                        row(
                            _iv_select.ifRequested("customers", () ->
                                DSL.row(
                                    DSL.multiset(
                                        DSL.select(_1_queryForQuery_address_customers(_iv_select))
                                        .from(_a_address_223244161_customer)
                                        .orderBy(_a_address_223244161_customer.fields(_a_address_223244161_customer.getPrimaryKey().getFieldsArray()))
                                    )
                                ).mapping(_iv_e -> _iv_e.map(Record1::value1))
                            )
                        ).mapping
                """
        );
    }

    @Test
    @DisplayName("Table column reference should be optional")
    void scalarReference() {
        assertGeneratedContentContains(
                "scalarReference",
                "_iv_select.ifRequested(\"cityId\", () ->"
        );
    }

    @Test
    @DisplayName("SplitQuery fields should not wrap target subquery with ifRequested")
    void splitQuery() {
        assertGeneratedContentContains(
                "splitQuery",
                "DSL.row(_a_customer.CUSTOMER_ID), DSL.field(DSL.select(addressForCustomer_address(_iv_select",
                "ifRequested(\"city\", () ->" // Check path on subquery
        );
    }

    @Test
    @DisplayName("Listed splitQuery fields should not wrap target subquery with ifRequested")
    void splitQueryList() {
        assertGeneratedContentContains(
                "splitQueryList",
                "DSL.row(_a_address.ADDRESS_ID), DSL.multiset(DSL.select(customersForAddress_customer(_iv_select"
        );
    }

    @Test
    @DisplayName("Entity queries should accept SelectionSet and wrap subqueries with ifRequested")
    void entityQuery() {
        assertGeneratedContentContains(
                "entity", Set.of(FEDERATION_QUERY),
                "_iv_inputMap, SelectionSet _iv_select)",
                """
                _a_customer.getId(),
                _iv_select.ifRequested("address", () ->
                    DSL.field(
                        DSL.select(QueryHelper.objectRow("id", _a_customer_2168032777_address.getId()))
                        .from(_a_customer_2168032777_address)
                    )
                )
                """
        );
    }

    @Test
    @DisplayName("Multitable queries should pass SelectionSet to data helpers but not sort helpers")
    void multitable() {
        assertGeneratedContentContains(
                "multitable",
                // Main method accepts SelectionSet
                "_iv_ctx, SelectionSet _iv_select)",

                // SelectionSet is not passed to sort fields helper methods
                "paymenttypetwoSortFieldsForPayments()",
                "JSONB>> paymenttypetwoSortFieldsForPayments()",

                // SelectionSet is passed to data helper methods
                "paymenttypeoneForPayments(_iv_select)",
                "paymenttypeoneForPayments(SelectionSet _iv_select)",

                ".AMOUNT, _iv_select.ifRequested(\"rental\", () ->"
        );
    }

    @Test
    @DisplayName("Multitable splitQuery should pass SelectionSet to data helpers but not sort helpers")
    void multitableSplitQuery() {
        assertGeneratedContentContains(
                "multitableSplitQuery",
                // Main method accepts SelectionSet
                "_iv_ctx, SelectionSet _iv_select)",

                // SelectionSet is not passed to sort fields helper methods
                "paymenttypetwoSortFieldsForPayments(_a_customer)",
                "JSONB>> paymenttypetwoSortFieldsForPayments(Customer _a_customer)",

                // SelectionSet is passed to data helper methods
                "paymenttypeoneForPayments(_iv_select)",
                "paymenttypeoneForPayments(SelectionSet _iv_select)",

                ".AMOUNT, _iv_select.ifRequested(\"rental\", () ->"
        );
    }

    @Test
    @DisplayName("Node queries should accept SelectionSet and wrap subqueries with ifRequested")
    void node() {
        assertGeneratedContentContains(
                "node", Set.of(NODE, NODE_QUERY),
                "_mi_id, SelectionSet _iv_select)",
                "customerForNode_customer(_iv_select)",
                "ifRequested(\"address\""
        );
    }

    @Test
    @DisplayName("Single table interface queries should accept SelectionSet and wrap subqueries")
    void singleTableInterface() {
        assertGeneratedContentContains(
                "singleTableInterface",
                "_iv_ctx, SelectionSet _iv_select)",
                "_iv_select.ifRequested(\"customer\", () ->"
        );
    }
}
