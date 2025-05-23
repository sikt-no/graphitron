package no.sikt.graphitron.wiring;

import no.sikt.graphitron.common.GeneratorTest;
import no.sikt.graphitron.common.configuration.SchemaComponent;
import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.generators.codeinterface.wiring.WiringClassGenerator;
import no.sikt.graphitron.generators.resolvers.datafetchers.operations.EntityFetcherClassGenerator;
import no.sikt.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.common.configuration.SchemaComponent.FEDERATION;

@DisplayName("Entity Wiring - Generation of the wiring for the entity fetcher")
public class WiringEntityFetcherTest extends GeneratorTest {
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
        var generator = new EntityFetcherClassGenerator(schema);
        return List.of(generator, new WiringClassGenerator(List.of(generator), schema));
    }

    @Test
    @DisplayName("Entity data fetcher exists")
    void defaultCase() {
        assertGeneratedContentContains(
                "entity",
                ".newTypeWiring(\"Query\").dataFetcher(\"_entities\", QueryEntityGeneratedDataFetcher.entityFetcher()"
        );
    }
}
