package no.sikt.graphitron.resolvers.type;

import no.sikt.graphitron.common.GeneratorTest;
import no.sikt.graphitron.common.configuration.SchemaComponent;
import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.generators.resolvers.datafetchers.typeresolvers.TypeResolverClassGenerator;
import no.sikt.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.common.configuration.SchemaComponent.FEDERATION_QUERY;

@DisplayName("Type resolvers - Resolvers for unions and interfaces")
public class TypeResolverTest extends GeneratorTest {
    @Override
    protected String getSubpath() {
        return "resolvers/type";
    }

    @Override
    protected Set<SchemaComponent> getComponents() {
        return makeComponents(FEDERATION_QUERY);
    }

    @Override
    protected List<ClassGenerator> makeGenerators(ProcessedSchema schema) {
        return List.of(new TypeResolverClassGenerator(schema));
    }

    @Test
    @DisplayName("Entity exists")
    void entity() {
        GeneratorConfig.setIncludeApolloFederation(true);
        assertGeneratedContentMatches("entity");
    }
}
