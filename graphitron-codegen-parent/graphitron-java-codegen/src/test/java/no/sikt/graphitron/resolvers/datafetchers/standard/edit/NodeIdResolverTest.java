package no.sikt.graphitron.resolvers.datafetchers.standard.edit;

import no.sikt.graphitron.common.GeneratorTest;
import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.generators.resolvers.datafetchers.operations.OperationClassGenerator;
import no.sikt.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.common.configuration.SchemaComponent.CUSTOMER_INPUT_TABLE;

@DisplayName("Mutation resolvers - Resolvers with nodeId for mutations")
public class NodeIdResolverTest extends GeneratorTest {
    @Override
    protected String getSubpath() {
        return "resolvers/datafetchers/edit/standard";
    }

    @BeforeAll
    static void setUp() {
        GeneratorConfig.setNodeStrategy(true);
    }

    @AfterAll
    static void tearDown() {
        GeneratorConfig.setNodeStrategy(false);
    }

    @Override
    protected List<ClassGenerator> makeGenerators(ProcessedSchema schema) {
        return List.of(new OperationClassGenerator(schema));
    }

    @Test
    @DisplayName("A mutation that uses nodeIdStrategy")
    void nodeId() {
        assertGeneratedContentContains(
                "nodeId", Set.of(CUSTOMER_INPUT_TABLE),
                ".mutationForMutation(transform.getCtx(), nodeIdStrategy, inRecord)",
                ".mutationForMutation(ctx, nodeIdStrategy, inRecord, selectionSet)"
        );
    }
}
