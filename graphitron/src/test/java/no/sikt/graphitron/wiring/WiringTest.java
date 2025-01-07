package no.sikt.graphitron.wiring;

import no.sikt.graphitron.common.GeneratorTest;
import no.sikt.graphitron.common.configuration.SchemaComponent;
import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.definitions.interfaces.GenerationTarget;
import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.generators.datafetchers.wiring.WiringClassGenerator;
import no.sikt.graphitron.reducedgenerators.dummygenerators.DummyEntityFetcherResolverClassGenerator;
import no.sikt.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.common.configuration.SchemaComponent.FEDERATION;

@DisplayName("Wiring - Generation of the method returning a runtime wiring")
public class WiringTest extends GeneratorTest {
    @Override
    protected String getSubpath() {
        return "wiring";
    }

    @Override
    protected Set<SchemaComponent> getComponents() {
        return makeComponents(FEDERATION);
    }

    @Override
    protected List<ClassGenerator<? extends GenerationTarget>> makeGenerators(ProcessedSchema schema) {
        var entityGenerator = new DummyEntityFetcherResolverClassGenerator(schema);
        return List.of(entityGenerator, new WiringClassGenerator(List.of(entityGenerator), schema));
    }

    @BeforeEach
    void before() {
        GeneratorConfig.setIncludeApolloFederation(true);
    }

    @Test
    @DisplayName("One data fetcher generator exists")
    void defaultCase() {
        assertGeneratedContentMatches("default");
    }
}
