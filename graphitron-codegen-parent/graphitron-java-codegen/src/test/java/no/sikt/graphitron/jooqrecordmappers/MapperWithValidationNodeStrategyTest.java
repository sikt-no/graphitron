package no.sikt.graphitron.jooqrecordmappers;

import no.sikt.graphitron.common.GeneratorTest;
import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.configuration.RecordValidation;
import no.sikt.graphitron.configuration.externalreferences.ExternalReference;
import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.generators.mapping.RecordMapperClassGenerator;
import no.sikt.graphitron.generators.mapping.TransformerClassGenerator;
import no.sikt.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.common.configuration.ReferencedEntry.*;
import static no.sikt.graphitron.common.configuration.SchemaComponent.CUSTOMER_NODE;

@DisplayName("Mappers with node strategy and validation (temporary test class)")
public class MapperWithValidationNodeStrategyTest extends GeneratorTest {
    @Override
    protected String getSubpath() {
        return "jooqmappers/nodeStrategy";
    }

    @Override
    protected Set<ExternalReference> getExternalReferences() {
        return makeReferences(DUMMY_SERVICE, DUMMY_RECORD, DUMMY_JOOQ_ENUM, MAPPER_FETCH_SERVICE);
    }

    @Override
    protected List<ClassGenerator> makeGenerators(ProcessedSchema schema) {
        return List.of(new RecordMapperClassGenerator(schema, false),
                new RecordMapperClassGenerator(schema, true),
                new TransformerClassGenerator(schema)
        );
    }

    @BeforeAll
    static void setUp() {
        GeneratorConfig.setNodeStrategy(true);
    }

    @AfterAll
    static void tearDown() {
        GeneratorConfig.setNodeStrategy(false);
    }

    @BeforeEach
    void before() {
        GeneratorConfig.setRecordValidation(new RecordValidation(true, null));
    }

    @Test
    @DisplayName("To record with node strategy (temporary test)")
    void toRecord() {
        assertGeneratedContentContains("toRecord/default", Set.of(CUSTOMER_NODE),
                // In mapper
                "toJOOQRecord(List<CustomerInputTable> customerInputTable, NodeIdStrategy nodeIdStrategy, String path, RecordTransformer transform)",
                "nodeIdStrategy.setId(customerRecord, itCustomerInputTable.getId(), \"CustomerNode\", Customer.CUSTOMER.getPrimaryKey().getFieldsArray())",

                // In recordtransformer
                "customerInputTableToJOOQRecord(List<CustomerInputTable> input, NodeIdStrategy nodeIdStrategy, String path, String indexPath)",
                "customerInputTableToJOOQRecord(CustomerInputTable input, NodeIdStrategy nodeIdStrategy, String path, String indexPath)",
                "customerInputTableToJOOQRecord(List.of(input), nodeIdStrategy, path, indexPath)",
                ".toJOOQRecord(input, nodeIdStrategy, path, this)",

                // validation
                "validate(List<CustomerRecord> customerRecordList, String path, RecordTransformer transform)",
                ".validate(records, indexPath, this)"
        );
    }
}
