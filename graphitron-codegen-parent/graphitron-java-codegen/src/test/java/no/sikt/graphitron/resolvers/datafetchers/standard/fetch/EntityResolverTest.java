package no.sikt.graphitron.resolvers.datafetchers.standard.fetch;

import no.sikt.graphitron.common.GeneratorTest;
import no.sikt.graphitron.common.configuration.SchemaComponent;
import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.generators.resolvers.datafetchers.operations.EntityFetcherClassGenerator;
import no.sikt.graphql.schema.ProcessedSchema;
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
    protected List<ClassGenerator> makeGenerators(ProcessedSchema schema) {
        return List.of(new EntityFetcherClassGenerator(schema));
    }

    @Test
    @DisplayName("One entity exists")
    void defaultCase() {
        assertGeneratedContentMatches("default");
    }

    @Test
    @DisplayName("No entities defined in schema")
    void noEntities() {
        assertGeneratedContentContains("noEntities", ".get(\"__typename\")) {default: return null;}");
    }

    @Test
    @DisplayName("Entity queries for two types")
    void twoTypes() {
        assertGeneratedContentContains(
                "twoTypes",
                "transformDTO(AddressDBQueries.addressAsEntity(",
                "transformDTO(CustomerDBQueries.customerAsEntity("
        );
    }
}
