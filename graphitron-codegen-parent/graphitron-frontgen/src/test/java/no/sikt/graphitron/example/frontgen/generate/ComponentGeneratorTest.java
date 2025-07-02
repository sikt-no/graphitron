package no.sikt.graphitron.example.frontgen.generate;

import no.sikt.graphitron.generate.GraphQLGenerator;
import no.sikt.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static no.sikt.graphitron.example.frontgen.generate.GeneratorTest.assertGeneratedContentMatches;

class ComponentGeneratorTest {

    private static final String BASE_PATH = "src/test/resources/";

    @BeforeEach
    void setUp() {
        TestConfiguration.setProperties();
    }

    @Test
    void customerTableComponent() {
        ProcessedSchema processedSchema = TestConfiguration.getProcessedSchema(BASE_PATH + "customerTableComponent", false, false);
        ComponentGenerator componentGenerator = new ComponentGenerator(processedSchema);
        assertGeneratedContentMatches(BASE_PATH + "customerTableComponent", GraphQLGenerator.generateAsStrings(List.of(componentGenerator)));
    }

    @Test
    void filmTableComponent() {
        ProcessedSchema processedSchema = TestConfiguration.getProcessedSchema(BASE_PATH + "filmTableComponent", false, false);
        ComponentGenerator componentGenerator = new ComponentGenerator(processedSchema);
        assertGeneratedContentMatches(BASE_PATH + "filmTableComponent", GraphQLGenerator.generateAsStrings(List.of(componentGenerator)));
    }

    @Test
    void languageTableComponent() {
        ProcessedSchema processedSchema = TestConfiguration.getProcessedSchema(BASE_PATH + "languageTableComponent", false, false);
        ComponentGenerator componentGenerator = new ComponentGenerator(processedSchema);
        assertGeneratedContentMatches(BASE_PATH + "languageTableComponent", GraphQLGenerator.generateAsStrings(List.of(componentGenerator)));
    }
}