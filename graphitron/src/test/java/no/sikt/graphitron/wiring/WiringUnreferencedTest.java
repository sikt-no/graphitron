package no.sikt.graphitron.wiring;

import no.sikt.graphitron.common.GeneratorTest;
import no.sikt.graphitron.definitions.interfaces.GenerationTarget;
import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.generators.datafetchers.wiring.WiringClassGenerator;
import no.sikt.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

@DisplayName("Wiring - Generation of the method returning a runtime wiring")
public class WiringUnreferencedTest extends GeneratorTest {
    @Override
    protected String getSubpath() {
        return "wiring";
    }

    @Override
    protected List<ClassGenerator<? extends GenerationTarget>> makeGenerators(ProcessedSchema schema) {
        return List.of(new WiringClassGenerator(List.of(), schema));
    }

    @Test
    @Disabled("Not supported yet.")
    @DisplayName("Unreferenced types exist")
    void unreferencedTypes() {
        assertGeneratedContentContains("unreferencedTypes", ".newTypeWiring(\"SomeType\")");
    }
}
