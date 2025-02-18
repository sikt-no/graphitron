package no.sikt.graphitron.wiring;

import no.sikt.graphitron.common.GeneratorTest;
import no.sikt.graphitron.common.configuration.SchemaComponent;
import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.generators.codeinterface.wiring.WiringClassGenerator;
import no.sikt.graphitron.generators.resolvers.datafetchers.typeresolvers.TypeResolverClassGenerator;
import no.sikt.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.common.configuration.SchemaComponent.FEDERATION;

@DisplayName("Entity Wiring - Generation of the type resolver wiring")
public class WiringEntityTypeResolverTest extends GeneratorTest {
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
        var generator = new TypeResolverClassGenerator(schema);
        return List.of(generator, new WiringClassGenerator(List.of(generator), schema.nodeExists()));
    }

    @BeforeEach
    void before() {
        GeneratorConfig.setIncludeApolloFederation(true);
    }

    @Test
    @DisplayName("Entity type resolver exists")
    void defaultCase() {
        assertGeneratedContentContains(
                "entity",
                ".newTypeWiring(\"_Entity\").typeResolver(EntityTypeResolver.entityTypeResolver()"
        );
    }
}
