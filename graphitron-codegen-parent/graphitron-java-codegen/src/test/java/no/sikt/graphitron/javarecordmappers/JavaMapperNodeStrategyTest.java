package no.sikt.graphitron.javarecordmappers;

import no.sikt.graphitron.common.GeneratorTest;
import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.configuration.externalreferences.ExternalReference;
import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.generators.mapping.JavaRecordMapperClassGenerator;
import no.sikt.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.common.configuration.ReferencedEntry.ID_RECORD;
import static no.sikt.graphitron.common.configuration.SchemaComponent.CUSTOMER_NODE;

@DisplayName("Java mappers with node strategy (temporary test class)")
public class JavaMapperNodeStrategyTest extends GeneratorTest {
    @Override
    protected String getSubpath() {
        return "javamappers/nodeStrategy";
    }

    @Override
    protected Set<ExternalReference> getExternalReferences() {
        return makeReferences(ID_RECORD);
    }

    @Override
    protected List<ClassGenerator> makeGenerators(ProcessedSchema schema) {
        return List.of(new JavaRecordMapperClassGenerator(schema, true));
    }
    @BeforeAll
    static void setUp() {
        GeneratorConfig.setNodeStrategy(true);
    }

    @AfterAll
    static void tearDown() {
        GeneratorConfig.setNodeStrategy(false);
    }

    @Test
    @DisplayName("Java record input should not use node strategy (temporary test)")
    void toRecord() {
        assertGeneratedContentContains("toRecord/default", Set.of(CUSTOMER_NODE),
                "iDJavaRecord.setId(_nit_dummyInputRecord.getId());"
        );
    }
}
