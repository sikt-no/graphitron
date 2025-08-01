package no.sikt.graphitron.jooqrecordmappers;

import no.sikt.graphitron.common.GeneratorTest;
import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.generators.mapping.RecordMapperClassGenerator;
import no.sikt.graphitron.generators.mapping.TransformerClassGenerator;
import no.sikt.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.common.configuration.SchemaComponent.CUSTOMER_NODE;

@DisplayName("Mappers with node strategy (temporary test class)")
public class MapperNodeStrategyTest extends GeneratorTest {
    @Override
    protected String getSubpath() {
        return "jooqmappers/nodeStrategy";
    }

    @Override
    protected List<ClassGenerator> makeGenerators(ProcessedSchema schema) {
        return List.of(
                new RecordMapperClassGenerator(schema, false),
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

    @Test
    @DisplayName("To graph with node strategy (temporary test)")
    void toGraph() {
        assertGeneratedContentContains("toGraph/default", Set.of(CUSTOMER_NODE),
                // In mapper
                " recordToGraphType(List<CustomerRecord> customerRecord, NodeIdStrategy nodeIdStrategy, String path, RecordTransformer transform)",
                "customerNode.setId(nodeIdStrategy.createId(itCustomerRecord, \"CustomerNode\", Customer.CUSTOMER.getPrimaryKey().getFieldsArray()))",

                // In record transformer
                "customerNodeRecordToGraphType(List<CustomerRecord> input, NodeIdStrategy nodeIdStrategy, String path)",
                "customerNodeRecordToGraphType(CustomerRecord input, NodeIdStrategy nodeIdStrategy, String path)",
                "customerNodeRecordToGraphType(List.of(input), nodeIdStrategy, path)",
                ".recordToGraphType(input, nodeIdStrategy, path, this)"
        );
    }

    @Test
    @DisplayName("To graph with node strategy (temporary test)")
    void toGraphNotNodeId() {
        assertGeneratedContentContains("toGraph/notNodeId",
                "customer.setCustomerId(itCustomerRecord.getCustomerId()"
        );
    }

    @Test
    @DisplayName("To record with node strategy (temporary test)")
    void toRecord() {
        assertGeneratedContentContains("toRecord/default", Set.of(CUSTOMER_NODE),
                // In mapper
                "toJOOQRecord(List<CustomerInputTable> customerInputTable, NodeIdStrategy nodeIdStrategy, String path, RecordTransformer transform)",
                "nodeIdStrategy.setId(customerRecord, itCustomerInputTable.getId(), \"CustomerNode\", Customer.CUSTOMER.getPrimaryKey().getFieldsArray())",

                // In recordtransformer
                "customerInputTableToJOOQRecord(List<CustomerInputTable> input, NodeIdStrategy nodeIdStrategy, String path)",
                "customerInputTableToJOOQRecord(CustomerInputTable input, NodeIdStrategy nodeIdStrategy, String path)",
                "customerInputTableToJOOQRecord(List.of(input), nodeIdStrategy, path)",
                ".toJOOQRecord(input, nodeIdStrategy, path, this)"
        );
    }

    @Test
    @DisplayName("Node ID in jooq record with implicit reference")
    void toRecordWithImplicitReference() {
        assertGeneratedContentContains("toRecord/withImplicitReference", Set.of(CUSTOMER_NODE),
                "nodeIdStrategy.setReferenceId(customerRecord, itCustomerInputTable.getAddressId(), \"Address\", Customer.CUSTOMER.ADDRESS_ID)"
        );
    }

    @Test
    @DisplayName("Node ID in jooq record with key reference")
    void toRecordWithReferenceKey() {
        assertGeneratedContentContains("toRecord/withReferenceKey", Set.of(CUSTOMER_NODE),
                "nodeIdStrategy.setReferenceId(customerRecord, itCustomerInputTable.getAddressId(), \"Address\", Customer.CUSTOMER.ADDRESS_ID)"
        );
    }
}
