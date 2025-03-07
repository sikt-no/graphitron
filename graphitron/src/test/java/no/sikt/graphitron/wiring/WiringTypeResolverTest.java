package no.sikt.graphitron.wiring;

import no.sikt.graphitron.common.GeneratorTest;
import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.generators.codeinterface.wiring.WiringClassGenerator;
import no.sikt.graphitron.generators.resolvers.datafetchers.typeresolvers.TypeResolverClassGenerator;
import no.sikt.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.common.configuration.SchemaComponent.FEDERATION;

@DisplayName("Type Resolver Wiring - Generation of the type resolver wiring")
public class WiringTypeResolverTest extends GeneratorTest {
    @Override
    protected String getSubpath() {
        return "wiring";
    }

    @Override
    protected List<ClassGenerator> makeGenerators(ProcessedSchema schema) {
        var generator = new TypeResolverClassGenerator(schema);
        return List.of(generator, new WiringClassGenerator(List.of(generator), schema.nodeExists()));
    }

    @Test
    @DisplayName("Interface type resolver exists")
    void interfaceType() {
        assertGeneratedContentContains("interface", ".newTypeWiring(\"I\").typeResolver(ITypeResolver.iTypeResolver()");
    }

    @Test
    @DisplayName("Union type resolver exists")
    void unionType() {
        assertGeneratedContentContains("union", ".newTypeWiring(\"U\").typeResolver(UTypeResolver.uTypeResolver()");
    }

    @Test
    @DisplayName("Entity type resolver exists")
    void entity() {
        assertGeneratedContentContains(
                "entity",
                Set.of(FEDERATION),
                ".newTypeWiring(\"_Entity\").typeResolver(EntityTypeResolver.entityTypeResolver()"
        );
    }
}
