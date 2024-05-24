package no.fellesstudentsystem.graphitron;

import no.fellesstudentsystem.graphitron.configuration.GeneratorConfig;
import no.fellesstudentsystem.graphitron.configuration.externalreferences.ExternalClassReference;
import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationTarget;
import no.fellesstudentsystem.graphitron.generators.abstractions.ClassGenerator;
import no.fellesstudentsystem.graphitron.generators.db.fetch.FetchDBClassGenerator;
import no.fellesstudentsystem.graphitron.generators.resolvers.fetch.FetchResolverClassGenerator;
import no.fellesstudentsystem.graphitron.generators.resolvers.mapping.JavaRecordMapperClassGenerator;
import no.fellesstudentsystem.graphitron.generators.resolvers.mapping.RecordMapperClassGenerator;
import no.fellesstudentsystem.graphitron.generators.resolvers.mapping.TransformerClassGenerator;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class GraphQLGeneratorQueryServiceTest extends TestCommon {
    public static final String SRC_TEST_RESOURCES_PATH = "query";

    private final List<ExternalClassReference> references = List.of(
            new ExternalClassReference("TEST_CUSTOMER_ADDRESS", "no.fellesstudentsystem.graphitron.conditions.CustomerTestConditions"),
            new ExternalClassReference("TEST_CUSTOMER", "no.fellesstudentsystem.graphitron.services.TestCustomerService")
    );

    public GraphQLGeneratorQueryServiceTest() {
        super(SRC_TEST_RESOURCES_PATH);
    }

    @Override
    protected Map<String, List<String>> generateFiles(String schemaParentFolder) throws IOException {
        var processedSchema = getProcessedSchema(schemaParentFolder);
        List<ClassGenerator<? extends GenerationTarget>> generators = List.of(
                new FetchDBClassGenerator(processedSchema),
                new FetchResolverClassGenerator(processedSchema),
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
    @Disabled
    void generate_queryWithService_shouldGenerateMappersAndCallSimpleServices() throws IOException {
        assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder("resolverWithService");
    }

    @Test
    @Disabled
    void generate_queryWithService_shouldGenerateMappersAndCallServicesWithPagination() throws IOException {
        assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder("resolverWithServicePagination");
    }

    @Test
    @Disabled
    void generate_queryWithService_shouldGenerateMappersAndCallServicesWithNestedTypes() throws IOException {
        assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder("resolverWithServiceNestedTypes");
    }
}
