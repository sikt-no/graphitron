package no.sikt.graphitron.wiring;

import no.sikt.graphitron.common.GeneratorTest;
import no.sikt.graphitron.common.configuration.SchemaComponent;
import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.definitions.interfaces.GenerationTarget;
import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.generators.datafetchers.wiring.WiringClassGenerator;
import no.sikt.graphitron.reducedgenerators.dummygenerators.DummyEntityFetcher2ResolverClassGenerator;
import no.sikt.graphitron.reducedgenerators.dummygenerators.DummyEntityFetcherResolverClassGenerator;
import no.sikt.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static no.sikt.graphitron.common.configuration.SchemaComponent.FEDERATION_QUERY;

@DisplayName("Wiring - Generation of the method returning a runtime wiring")
public class WiringTwoFetchersTest extends GeneratorTest {
    @Override
    protected String getSubpath() {
        return "wiring";
    }

    @Override
    protected Set<SchemaComponent> getComponents() {
        return makeComponents(FEDERATION_QUERY);
    }

    @Override
    protected List<ClassGenerator<? extends GenerationTarget>> makeGenerators(ProcessedSchema schema) {
        var generators = List.<ClassGenerator<? extends GenerationTarget>>of(
                new DummyEntityFetcherResolverClassGenerator(schema),
                new DummyEntityFetcher2ResolverClassGenerator(schema)
        );
        return Stream.concat(generators.stream(), Stream.of(new WiringClassGenerator(generators, schema))).toList();
    }

    @BeforeEach
    void before() {
        GeneratorConfig.setIncludeApolloFederation(true);
    }

    @Test
    @DisplayName("Two data fetcher generators exist")
    void twoFetchers() {
        assertGeneratedContentContains(
                "twoFetchers",
                "TypeRuntimeWiring.newTypeWiring(\"Query\")" +
                        ".dataFetcher(\"_entities\", QueryEntityGeneratedDataFetcher.entityFetcher())" +
                        ".dataFetcher(\"_entities\", QueryEntity2GeneratedDataFetcher.entityFetcher())"
        );
    }
}
