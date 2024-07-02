package no.fellesstudentsystem.graphitron;

import no.fellesstudentsystem.graphitron.configuration.GeneratorConfig;
import no.fellesstudentsystem.graphitron.configuration.externalreferences.ExternalClassReference;
import no.fellesstudentsystem.graphitron.configuration.externalreferences.ExternalReference;
import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationTarget;
import no.fellesstudentsystem.graphitron.generators.abstractions.ClassGenerator;
import no.fellesstudentsystem.graphitron.generators.resolvers.mapping.JavaRecordMapperClassGenerator;
import no.fellesstudentsystem.graphitron.generators.resolvers.mapping.RecordMapperClassGenerator;
import no.fellesstudentsystem.graphitron.generators.resolvers.mapping.TransformerClassGenerator;
import no.fellesstudentsystem.graphitron.records.TestCustomerRecord;
import no.fellesstudentsystem.graphitron.services.TestCustomerService;
import no.fellesstudentsystem.graphitron.services.TestFetchCustomerService;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class GraphQLGeneratorServiceOverlapTest extends TestCommon {
    public static final String SRC_TEST_RESOURCES_PATH = "serviceOverlap";

    private final List<ExternalReference> references = List.of(
            new ExternalClassReference("TEST_FETCH_CUSTOMER", TestFetchCustomerService.class),
            new ExternalClassReference("TEST_CUSTOMER", TestCustomerService.class),
            new ExternalClassReference("TEST_CUSTOMER_RECORD", TestCustomerRecord.class)
    );

    public GraphQLGeneratorServiceOverlapTest() {
        super(SRC_TEST_RESOURCES_PATH);
    }

    @Override
    protected Map<String, List<String>> generateFiles(String schemaParentFolder) throws IOException {
        var processedSchema = getProcessedSchema(schemaParentFolder);
        List<ClassGenerator<? extends GenerationTarget>> generators = List.of(
                new TransformerClassGenerator(processedSchema),
                new RecordMapperClassGenerator(processedSchema, true),
                new RecordMapperClassGenerator(processedSchema, false),
                new JavaRecordMapperClassGenerator(processedSchema, true),
                new JavaRecordMapperClassGenerator(processedSchema, false)
        );

        return generateFiles(generators);
    }

    @Override
    protected void setProperties() {
        GeneratorConfig.setProperties(
                Set.of(),
                tempOutputDirectory.toString(),
                DEFAULT_OUTPUT_PACKAGE,
                DEFAULT_JOOQ_PACKAGE,
                references,
                List.of(),
                List.of()
        );
    }

    @Test
    void generate_servicesReusingRecordsForQueryAndMutation_shouldNotDuplicateTransformMethods() throws IOException {
        assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder("servicesReusingRecordsForQueryAndMutation");
    }
}
