package no.sikt.graphitron.queries.fetch;

import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.reducedgenerators.UnionOnlyFetchDBClassGenerator;
import no.sikt.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.common.configuration.SchemaComponent.*;

@DisplayName("Node ID input - entity input fields with nodeId directive")
public class EntityNodeIdInputTest extends NodeIdDirectiveTest {
    @Override
    protected String getSubpath() {
        return super.getSubpath() + "input";
    }

    @Override
    protected List<ClassGenerator> makeGenerators(ProcessedSchema schema) {
        return List.of(new UnionOnlyFetchDBClassGenerator(schema));
    }

    @Test
    @DisplayName("NodeId is handled correctly for entity representations input")
    void inEntitiesQuery() {
        assertGeneratedContentContains("inEntitiesQuery", Set.of(FEDERATION_QUERY),
                ".where(_iv_nodeIdStrategy.hasIds(\"Customer\", _mi_representations_filtered.stream().map(_iv_it -> (String) _iv_it.get(\"id\")).filter(Objects::nonNull).toList(), _a_customer.fields("
        );
    }
}
