package no.sikt.graphitron.resolvers.standard.fetch;

import no.sikt.graphitron.common.GeneratorTest;
import no.sikt.graphitron.common.configuration.SchemaComponent;
import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.definitions.interfaces.GenerationTarget;
import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.reducedgenerators.EntityFetcherResolverOnlyUnionClassGenerator;
import no.sikt.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.common.configuration.SchemaComponent.FEDERATION_QUERY;

@DisplayName("Entity type resolvers - Resolvers for the entity union")
public class EntityTypeResolverTest extends GeneratorTest {
    @Override
    protected String getSubpath() {
        return "resolvers/fetch/entity";
    }

    @Override
    protected Set<SchemaComponent> getComponents() {
        return makeComponents(FEDERATION_QUERY);
    }

    @Override
    protected List<ClassGenerator<? extends GenerationTarget>> makeGenerators(ProcessedSchema schema) {
        return List.of(new EntityFetcherResolverOnlyUnionClassGenerator(schema));
    }

    @BeforeEach
    void before() {
        GeneratorConfig.setIncludeApolloFederation(true);
    }

    @Test
    @DisplayName("Entity exists")
    void union() {
        assertGeneratedContentMatches("union");
    }
}
