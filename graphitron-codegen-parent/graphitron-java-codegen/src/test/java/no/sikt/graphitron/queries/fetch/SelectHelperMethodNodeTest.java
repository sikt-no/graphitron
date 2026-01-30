package no.sikt.graphitron.queries.fetch;

import no.sikt.graphitron.common.GeneratorTest;
import no.sikt.graphitron.common.configuration.SchemaComponent;
import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.reducedgenerators.NodeOnlyHelperDBClassGenerator;
import no.sikt.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.common.configuration.SchemaComponent.NODE;

@DisplayName("Helper method generation and naming for node queries")
public class SelectHelperMethodNodeTest extends GeneratorTest {
    @Override
    protected String getSubpath() {
        return "queries/fetch/selectHelperMethodsNode";
    }

    @Override
    protected Set<SchemaComponent> getComponents() {
        return makeComponents(NODE);
    }

    @Override
    protected List<ClassGenerator> makeGenerators(ProcessedSchema schema) {
        return List.of(new NodeOnlyHelperDBClassGenerator(schema));
    }

    @Test
    @DisplayName("Node with multiple fields")
    void manyFields() {
        assertGeneratedContentContains("manyFields", "customer.FIRST_NAME", "customer.LAST_NAME");
    }

    @Test
    @DisplayName("Node type that has nested list references and thus generates multiple layers of helper methods")
    void nestedLists() {
        assertGeneratedContentContains("nestedLists",
                "customerForNode_customer() {",
                "DSL.multiset(DSL.select(_1_customerForNode_customer_addresses())",
                "_1_customerForNode_customer_addresses() {",
                "DSL.multiset(DSL.select(_2_customerForNode_customer_addresses_stores())",
                "_2_customerForNode_customer_addresses_stores() {"
        );
    }
}
