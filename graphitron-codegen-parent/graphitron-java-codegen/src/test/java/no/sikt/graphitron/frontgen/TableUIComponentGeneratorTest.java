package no.sikt.graphitron.frontgen;

import no.sikt.graphitron.common.GeneratorTest;
import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.generators.frontgen.TableUIComponentGenerator;
import no.sikt.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

class TableUIComponentGeneratorTest extends GeneratorTest {

    @Override
    protected String getSubpath() {
        return "frontgen";
    }

    @Override
    protected List<ClassGenerator> makeGenerators(ProcessedSchema schema) {
        return List.of(new TableUIComponentGenerator(schema));
    }

    @Test
    @DisplayName("Should create a component for each table, also supporting input")
    void customerTableComponent() {
        assertGeneratedContentMatches( "customerTableComponent");
    }

    @Test
    void filmTableComponent() {
        assertGeneratedContentMatches("filmTableComponent");
    }

    @Test
    void languageTableComponent() {
        assertGeneratedContentMatches("languageTableComponent");
    }

    @Test
    @DisplayName("Should skip fields with @notGenerated annotation")
    void cityTableComponent() {
        assertGeneratedContentMatches("cityTableComponent");
    }
}