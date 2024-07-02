package no.fellesstudentsystem.graphitron;

import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationTarget;
import no.fellesstudentsystem.graphitron.generators.abstractions.ClassGenerator;
import no.fellesstudentsystem.graphitron.generators.resolvers.mapping.JavaRecordMapperClassGenerator;
import no.fellesstudentsystem.graphitron.generators.resolvers.mapping.RecordMapperClassGenerator;
import no.fellesstudentsystem.graphitron.generators.resolvers.mapping.TransformerClassGenerator;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.Test;

import java.util.List;

import static no.fellesstudentsystem.graphitron.TestReferenceSet.*;

public class GraphQLGeneratorServiceOverlapTest extends GeneratorTest {
    public static final String SRC_TEST_RESOURCES_PATH = "serviceOverlap";

    public GraphQLGeneratorServiceOverlapTest() {
        super(SRC_TEST_RESOURCES_PATH, List.of(SERVICE_FETCH_CUSTOMER.get(), SERVICE_CUSTOMER.get(), RECORD_CUSTOMER.get()));
    }

    @Override
    protected List<ClassGenerator<? extends GenerationTarget>> makeGenerators(ProcessedSchema schema) {
        return List.of(
                new TransformerClassGenerator(schema),
                new RecordMapperClassGenerator(schema, true),
                new RecordMapperClassGenerator(schema, false),
                new JavaRecordMapperClassGenerator(schema, true),
                new JavaRecordMapperClassGenerator(schema, false)
        );
    }

    @Test
    void generate_servicesReusingRecordsForQueryAndMutation_shouldNotDuplicateTransformMethods() {
        assertGeneratedContentMatches("servicesReusingRecordsForQueryAndMutation");
    }
}
