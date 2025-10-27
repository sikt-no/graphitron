package no.sikt.graphitron.codeinterface;

import no.sikt.graphitron.common.GeneratorTest;
import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.generators.codeinterface.CodeInterfaceClassGenerator;
import no.sikt.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.common.configuration.SchemaComponent.NODE;

@DisplayName("Graphitron - Generation of the code interface methods")
public class GraphitronTest extends GeneratorTest {
    @Override
    protected String getSubpath() {
        return "codeinterface";
    }

    @Override
    protected List<ClassGenerator> makeGenerators(ProcessedSchema schema) {
        return List.of(new CodeInterfaceClassGenerator(schema));
    }

    @Test
    @DisplayName("Default code interface")
    void defaultCase() {
        assertGeneratedContentMatches("default");
    }

    @Test
    @DisplayName("Node is enabled")
    void node() {
        assertGeneratedContentContains(
                "node", Set.of(NODE),
                "getRuntimeWiringBuilder(NodeIdHandler _iv_nodeIdHandler",
                ".getRuntimeWiringBuilder(_iv_nodeIdHandler",
                "getRuntimeWiring(NodeIdHandler _iv_nodeIdHandler",
                "return getRuntimeWiringBuilder(_iv_nodeIdHandler",
                "getSchema(NodeIdHandler _iv_nodeIdHandler)",
                "= getRuntimeWiringBuilder(_iv_nodeIdHandler)"
        );
    }
}
