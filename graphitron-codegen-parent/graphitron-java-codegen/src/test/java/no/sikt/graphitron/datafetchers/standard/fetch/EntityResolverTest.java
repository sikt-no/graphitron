package no.sikt.graphitron.datafetchers.standard.fetch;

import no.sikt.graphitron.common.GeneratorTest;
import no.sikt.graphitron.common.configuration.SchemaComponent;
import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.generators.datafetchers.operations.OperationClassGenerator;
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
        return "datafetchers/fetch/entity";
    }

    @Override
    protected Set<SchemaComponent> getComponents() {
        return makeComponents(FEDERATION_QUERY);
    }

    @Override
    protected List<ClassGenerator> makeGenerators(ProcessedSchema schema) {
        return List.of(new OperationClassGenerator(schema));
    }

    @Test
    @DisplayName("One entity exists")
    void defaultCase() {
        assertGeneratedContentContains("default", "List<Map<String, Object>> _mi_representations = _iv_env.getArgument(\"representations\")");
    }

    @Test  // Dummy datafetcher when there are no entities, but _entities is defined. Should not happen in practice.
    @DisplayName("No entities defined in schema")
    void noEntities() {
        assertGeneratedContentContains("noEntities", "return _iv_env -> return null;");
    }
}
