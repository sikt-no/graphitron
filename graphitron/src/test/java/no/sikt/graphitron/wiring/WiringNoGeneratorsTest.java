package no.sikt.graphitron.wiring;

import no.sikt.graphitron.common.GeneratorTest;
import no.sikt.graphitron.common.configuration.SchemaComponent;
import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.generators.codeinterface.wiring.WiringClassGenerator;
import no.sikt.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.common.configuration.SchemaComponent.FEDERATION;

@DisplayName("Wiring - Generation of the method returning a runtime wiring")
public class WiringNoGeneratorsTest extends GeneratorTest {
    @Override
    protected String getSubpath() {
        return "wiring";
    }

    @Override
    protected Set<SchemaComponent> getComponents() {
        return makeComponents(FEDERATION);
    }

    @Override
    protected List<ClassGenerator> makeGenerators(ProcessedSchema schema) {
        return List.of(new WiringClassGenerator(List.of(), schema.nodeExists()));
    }

    @Test
    @DisplayName("No other generator exists")
    void noFetchers() {
        assertGeneratedContentContains("noGenerators", ".newRuntimeWiring();return wiring;");
    }
}
