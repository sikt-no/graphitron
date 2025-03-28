package no.sikt.graphitron.wiring;

import no.sikt.graphitron.common.GeneratorTest;
import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.generators.codeinterface.wiring.WiringClassGenerator;
import no.sikt.graphitron.generators.resolvers.datafetchers.update.UpdateClassGenerator;
import no.sikt.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.common.configuration.SchemaComponent.CUSTOMER_INPUT_TABLE;
import static no.sikt.graphitron.common.configuration.SchemaComponent.CUSTOMER_TABLE;

@DisplayName("Mutation Wiring - Generation of the method returning a runtime wiring builder")
public class WiringBuilderMutationTest extends GeneratorTest {
    @Override
    protected String getSubpath() {
        return "wiring";
    }

    @Override
    protected List<ClassGenerator> makeGenerators(ProcessedSchema schema) {
        var generator = new UpdateClassGenerator(schema);
        return List.of(generator, new WiringClassGenerator(List.of(generator), schema));
    }

    @Test
    @DisplayName("One mutation data fetcher generator exists")
    void defaultCase() {
        assertGeneratedContentContains(
                "mutation",
                Set.of(CUSTOMER_TABLE, CUSTOMER_INPUT_TABLE),
                ".newTypeWiring(\"Mutation\").dataFetcher(\"mutation\", MutationGeneratedDataFetcher.mutation()"
        );
    }
}
