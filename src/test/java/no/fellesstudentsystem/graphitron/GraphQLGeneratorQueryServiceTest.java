package no.fellesstudentsystem.graphitron;

import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationTarget;
import no.fellesstudentsystem.graphitron.generators.abstractions.ClassGenerator;
import no.fellesstudentsystem.graphitron.generators.db.fetch.FetchDBClassGenerator;
import no.fellesstudentsystem.graphitron.generators.resolvers.fetch.FetchResolverClassGenerator;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.Test;

import java.util.List;

import static no.fellesstudentsystem.graphitron.TestReferenceSet.*;

public class GraphQLGeneratorQueryServiceTest extends GeneratorTest {
    public static final String SRC_TEST_RESOURCES_PATH = "query";

    public GraphQLGeneratorQueryServiceTest() {
        super(
                SRC_TEST_RESOURCES_PATH,
                List.of(
                        CONDITION_CUSTOMER_ADDRESS.get(),
                        SERVICE_FETCH_CUSTOMER.get(),
                        SERVICE_FETCH_CITY.get(),
                        RECORD_CUSTOMER.get(),
                        RECORD_ADDRESS.get(),
                        RECORD_CITY.get(),
                        RECORD_ID.get()
                )
        );
    }

    @Override
    protected List<ClassGenerator<? extends GenerationTarget>> makeGenerators(ProcessedSchema schema) {
        return List.of(
                new FetchDBClassGenerator(schema), // Need to test that queries are NOT generated.
                new FetchResolverClassGenerator(schema)
        );
    }

    @Test
    void generate_queryWithService_shouldGenerateMappersAndCallSimpleServices() {
        assertGeneratedContentMatches("resolverWithService");
    }

    @Test
    void generate_queryWithService_shouldGenerateMappersForServicesInResolvers() {
        assertGeneratedContentMatches("resolverWithServiceAndNestedResolvers");
    }

    @Test
    void generate_queryWithService_shouldGenerateMappersAndCallServicesWithPagination() {
        assertGeneratedContentMatches("resolverWithServicePagination");
    }

    @Test
    void generate_queryWithService_shouldGenerateMappersAndCallServicesWithNestedRecordTypes() {
        assertGeneratedContentMatches("resolverWithServiceNestedRecordTypes");
    }

    @Test
    void generate_queryWithService_shouldGenerateMappersAndCallServicesWithJavaRecordOutputs() {
        assertGeneratedContentMatches("resolverWithServiceJavaRecordOutputs");
    }
}
