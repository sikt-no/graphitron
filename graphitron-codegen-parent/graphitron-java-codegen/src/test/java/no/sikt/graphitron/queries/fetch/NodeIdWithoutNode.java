package no.sikt.graphitron.queries.fetch;

import no.sikt.graphitron.common.GeneratorTest;
import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.reducedgenerators.MapOnlyFetchDBClassGenerator;
import no.sikt.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.common.configuration.SchemaComponent.CUSTOMER;
import static no.sikt.graphitron.common.configuration.SchemaComponent.CUSTOMER_QUERY;
import static no.sikt.graphitron.generators.codebuilding.VariableNames.VAR_NODE_STRATEGY;

@DisplayName("Wiring - Generation of the method returning a runtime wiring builder")
public class NodeIdWithoutNode extends GeneratorTest {
    @Override
    protected String getSubpath() {
        return "queries/fetch/nodeId";
    }

    @Override
    protected List<ClassGenerator> makeGenerators(ProcessedSchema schema) {
        return List.of(new MapOnlyFetchDBClassGenerator(schema));
    }

    @Test
    @DisplayName("Node strategy is not used when enabled but no Node interface exists")
    void nodeStrategyWithoutNode() {
        GeneratorConfig.setNodeStrategy(true);
        resultDoesNotContain(Set.of(CUSTOMER_QUERY, CUSTOMER), "NodeIdStrategy", VAR_NODE_STRATEGY);
        GeneratorConfig.setNodeStrategy(false);
    }
}
