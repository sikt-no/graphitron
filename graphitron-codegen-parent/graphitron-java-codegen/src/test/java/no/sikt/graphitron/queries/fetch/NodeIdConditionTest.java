package no.sikt.graphitron.queries.fetch;

import no.sikt.graphitron.configuration.externalreferences.ExternalReference;
import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.reducedgenerators.InterfaceOnlyFetchDBClassGenerator;
import no.sikt.graphitron.reducedgenerators.MapOnlyFetchDBClassGenerator;
import no.sikt.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.common.configuration.ReferencedEntry.QUERY_FETCH_CONDITION;

@DisplayName("Fetch query conditions on nodeId fields")
public class NodeIdConditionTest extends NodeIdDirectiveTest {

    @Override
    protected String getSubpath() {
        return "queries/fetch/conditions";
    }

    @Override
    protected Set<ExternalReference> getExternalReferences() {
        return makeReferences(QUERY_FETCH_CONDITION);
    }

    @Override
    protected List<ClassGenerator> makeGenerators(ProcessedSchema schema) {
        return List.of(new MapOnlyFetchDBClassGenerator(schema), new InterfaceOnlyFetchDBClassGenerator(schema));
    }

    @Test
    @DisplayName("Node ID parameter is decoded to TableRecord when condition method expects TableRecord")
    void onNodeIdParam() {
        assertGeneratedContentContains(
                "onNodeIdParam",
                ".queryCustomerNodeId(_a_customer, _iv_nodeIdStrategy.nodeIdToTableRecord(_mi_customerId, \"C\", List.of(CUSTOMER.CUSTOMER_ID))))"
        );
    }

    @Test
    @DisplayName("Node ID parameter is passed as String when condition method expects String")
    void onNodeIdParamAsString() {
        assertGeneratedContentContains(
                "onNodeIdParamAsString",
                ".queryCustomerNodeIdAsString(_a_customer, _mi_customerId)"
        );
    }

    @Test
    @DisplayName("Listed Node ID parameter is decoded to TableRecords when condition method expects List<TableRecord>")
    void onListedNodeIdParam() {
        assertGeneratedContentContains(
                "onListedNodeIdParam",
                ".queryCustomerNodeIds(_a_customer, _mi_customerIds.stream().map(_iv_it -> _iv_nodeIdStrategy.nodeIdToTableRecord(_iv_it, \"C\", List.of(CUSTOMER.CUSTOMER_ID))).toList())"
        );
    }

    @Test
    @DisplayName("Listed Node ID parameter is passed as Strings when condition method expects List<String>")
    void onListedNodeIdParamAsString() {
        assertGeneratedContentContains(
                "onListedNodeIdParamAsString",
                ".queryCustomerNodeIdsAsString(_a_customer, _mi_customerIds)"
        );
    }

    @Test
    @DisplayName("Nested node ID parameter is decoded to TableRecord when condition method expects TableRecord")
    void onNestedNodeIdParam() {
        assertGeneratedContentContains(
                "onNestedNodeIdParam",
                ".queryCustomerNodeId(_a_customer, _iv_nodeIdStrategy.nodeIdToTableRecord("
        );
    }

    @Test
    @DisplayName("Nested node ID parameter is decoded to TableRecord and other input fields are passed through unchanged")
    void onNestedNodeIdParamWithOtherField() {
        assertGeneratedContentContains(
                "onNestedNodeIdParamWithOtherField",
                ".queryCustomerIdAndNodeId(_a_customer, _mi_filter.getCustomerId(), _iv_nodeIdStrategy.nodeIdToTableRecord("
        );
    }
}
