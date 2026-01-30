package no.sikt.graphitron.queries.edit;

import no.sikt.graphitron.common.GeneratorTest;
import no.sikt.graphitron.common.configuration.SchemaComponent;
import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.reducedgenerators.UpdateOnlyDBClassGenerator;
import no.sikt.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.generators.codebuilding.VariableNames.VAR_NODE_STRATEGY;

@DisplayName("Mutation queries - Node strategy is not used when Node interface is absent")
public class MutationNodeIdWithoutNodeTest extends GeneratorTest {
    @Override
    protected String getSubpath() {
        return "queries/edit/delete";
    }

    @Override
    protected List<ClassGenerator> makeGenerators(ProcessedSchema schema) {
        return List.of(new UpdateOnlyDBClassGenerator(schema));
    }

    @Test
    @DisplayName("Node strategy is not used in delete mutation when enabled but no Node interface exists")
    void nodeStrategyWithoutNodeInDeleteMutation() {
        GeneratorConfig.setNodeStrategy(true);
        GeneratorConfig.setUseJdbcBatchingForDeletes(false);
        resultDoesNotContain("nodeIdWithoutNode", Set.of(SchemaComponent.CUSTOMER_INPUT_TABLE), "NodeIdStrategy", VAR_NODE_STRATEGY);
        GeneratorConfig.setNodeStrategy(false);
        GeneratorConfig.setUseJdbcBatchingForDeletes(true);
    }
}