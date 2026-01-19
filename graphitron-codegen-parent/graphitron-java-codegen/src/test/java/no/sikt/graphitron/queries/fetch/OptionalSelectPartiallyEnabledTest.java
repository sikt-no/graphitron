package no.sikt.graphitron.queries.fetch;

import no.sikt.graphitron.common.GeneratorTest;
import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.reducedgenerators.EntityFetchOnlyDBClassGenerator;
import no.sikt.graphitron.reducedgenerators.InterfaceOnlyFetchDBClassGenerator;
import no.sikt.graphitron.reducedgenerators.MapOnlyFetchDBClassGenerator;
import no.sikt.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.common.configuration.SchemaComponent.*;

abstract class OptionalSelectPartiallyEnabledTest extends GeneratorTest {
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

    @AfterAll
    static void tearDown() {
        GeneratorConfig.setUseOptionalSelects(false);
    }

    @Test
    @DisplayName("SelectionSet should be passed to helper methods")
    void selectionSetIsPassedToHelpers() {
        assertGeneratedContentContains(
                "selectionHelpers",
                "queryForQuery_customer(SelectionSet _iv_select)",
                "_1_queryForQuery_customer_address(SelectionSet _iv_select)"
        );
    }

    @Test
    @DisplayName("Entity queries should accept SelectionSet")
    void entityQuery() {
        assertGeneratedContentContains(
                "entity", Set.of(FEDERATION_QUERY),
                "_iv_inputMap, SelectionSet _iv_select)"
        );
    }

    @Test
    @DisplayName("Multitable queries should pass SelectionSet to data helpers")
    void multitable() {
        assertGeneratedContentContains(
                "multitable",
                "_iv_ctx, SelectionSet _iv_select)",
                "paymenttypeoneForPayments(_iv_select)",
                "paymenttypeoneForPayments(SelectionSet _iv_select)"
        );
    }

    @Test
    @DisplayName("Node queries should accept SelectionSet")
    void node() {
        assertGeneratedContentContains(
                "node", Set.of(NODE, NODE_QUERY),
                "_mi_id, SelectionSet _iv_select)",
                "customerForNode_customer(_iv_select)"
        );
    }

    @Test
    @DisplayName("Single table interface queries should accept SelectionSet")
    void singleTableInterface() {
        assertGeneratedContentContains(
                "singleTableInterface",
                "_iv_ctx, SelectionSet _iv_select)"
        );
    }
}