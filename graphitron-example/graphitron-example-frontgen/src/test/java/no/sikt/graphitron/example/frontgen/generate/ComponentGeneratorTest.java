package no.sikt.graphitron.example.frontgen.generate;

import no.sikt.graphitron.generate.GraphQLGenerator;
import no.sikt.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.Test;

import java.util.List;

import static no.sikt.graphitron.example.frontgen.generate.GeneratorTest.assertGeneratedContentMatches;

class ComponentGeneratorTest {

    @Test
    void generateAll() {
        TestConfiguration.setProperties();
        ProcessedSchema processedSchema = TestConfiguration.getProcessedSchema("src/test/resources/tableComponent", false, false);
        ComponentGenerator componentGenerator = new ComponentGenerator(processedSchema);
        assertGeneratedContentMatches("src/test/resources/tableComponent", GraphQLGenerator.generateAsStrings(List.of(componentGenerator)));
    }
}