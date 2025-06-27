package no.sikt.graphitron.example.frontgen.generate;

import no.sikt.graphitron.generate.GraphQLGenerator;
import no.sikt.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ComponentGeneratorTest {

    @Test
    void generateAll() {
        TestConfiguration.setProperties();
        ProcessedSchema processedSchema = TestConfiguration.getProcessedSchema("src/test/resources/", false, false);
        ComponentGenerator componentGenerator = new ComponentGenerator(processedSchema);
        Map<String, List<String>> stringListMap = GraphQLGenerator.generateAsStrings(List.of(componentGenerator));
        assertNotNull(stringListMap);
    }
}