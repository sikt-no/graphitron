package no.sikt.graphitron.frontgen;

import no.sikt.graphitron.common.GeneratorTest;
import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.generators.frontgen.QueryComponentsGenerator;
import no.sikt.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.Test;

import java.util.List;

class QueryComponentsGeneratorTest extends GeneratorTest {

    @Override
    protected String getSubpath() {
        return "frontgen";
    }

    @Override
    protected List<ClassGenerator> makeGenerators(ProcessedSchema schema) {
        return List.of(new QueryComponentsGenerator(schema));
    }

    @Test
    void queryComponents() {
        assertGeneratedContentMatches( "queryComponents");
    }

}