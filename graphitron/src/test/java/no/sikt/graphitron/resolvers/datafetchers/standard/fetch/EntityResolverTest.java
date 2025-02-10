package no.sikt.graphitron.resolvers.datafetchers.standard.fetch;

import no.sikt.graphitron.common.GeneratorTest;
import no.sikt.graphitron.common.configuration.SchemaComponent;
import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.definitions.interfaces.GenerationTarget;
import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.reducedgenerators.EntityFetcherOnlyFieldClassGenerator;
import no.sikt.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.common.configuration.SchemaComponent.FEDERATION_QUERY;

@DisplayName("Entity resolvers - Resolvers for the entity field")
public class EntityResolverTest extends GeneratorTest {
    @Override
    protected String getSubpath() {
        return "resolvers/datafetchers/fetch/entity";
    }

    @Override
    protected Set<SchemaComponent> getComponents() {
        return makeComponents(FEDERATION_QUERY);
    }

    @Override
    protected List<ClassGenerator<? extends GenerationTarget>> makeGenerators(ProcessedSchema schema) {
        return List.of(new EntityFetcherOnlyFieldClassGenerator(schema));
    }

    @BeforeEach
    void before() {
        GeneratorConfig.setIncludeApolloFederation(true);
    }

    @Test
    @DisplayName("One entity exists")
    void defaultCase() {
        assertGeneratedContentMatches("default");
    }

    @Test
    @DisplayName("No entities defined in schema")
    void noEntities() {
        assertGeneratedContentContains("noEntities", "switch (_typeName) {default: return null;}");
    }

    @Test
    @DisplayName("Entity queries for two types")
    void twoTypes() {
        assertGeneratedContentContains(
                "twoTypes",
                "\"Address\": _obj.putAll(AddressDBQueries.addressAsEntity(",
                "\"Customer\": _obj.putAll(CustomerDBQueries.customerAsEntity("
        );
    }
}
