package no.sikt.graphitron.wiring;

import no.sikt.graphitron.common.GeneratorTest;
import no.sikt.graphitron.common.configuration.SchemaComponent;
import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.definitions.interfaces.GenerationTarget;
import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.generators.resolvers.datafetchers.fetch.EntityFetcherClassGenerator;
import no.sikt.graphitron.generators.wiring.WiringClassGenerator;
import no.sikt.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.common.configuration.SchemaComponent.FEDERATION;

@DisplayName("Entity Wiring - Generation of the method returning a runtime wiring")
public class WiringEntityTest extends GeneratorTest {
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
        var generator = new EntityFetcherClassGenerator(schema);
        return List.of(generator, new WiringClassGenerator(List.of(generator), schema));
    }

    @BeforeEach
    void before() {
        GeneratorConfig.setIncludeApolloFederation(true);
    }

    @Test
    @DisplayName("Entity data fetcher exists")
    void defaultCase() {
        assertGeneratedContentContains(
                "entity",
                ".newTypeWiring(\"Query\").dataFetcher(\"_entities\", QueryEntityGeneratedDataFetcher.entityFetcher()",
                ".newTypeWiring(\"_Entity\").typeResolver(QueryEntityGeneratedDataFetcher.entityTypeResolver()"
        );
    }
}
