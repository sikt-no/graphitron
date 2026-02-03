package no.sikt.graphitron.jooqrecordmappers;

import no.sikt.graphitron.common.GeneratorTest;
import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.configuration.externalreferences.ExternalReference;
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

import static no.sikt.graphitron.common.configuration.ReferencedEntry.JAVA_RECORD_CUSTOMER;
import static no.sikt.graphitron.common.configuration.ReferencedEntry.MAPPER_FETCH_SERVICE;
import static no.sikt.graphitron.common.configuration.SchemaComponent.CUSTOMER_NODE;
import static no.sikt.graphitron.common.configuration.SchemaComponent.NODE;

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

    @Override
    protected Set<ExternalReference> getExternalReferences() {
        return makeReferences(JAVA_RECORD_CUSTOMER, MAPPER_FETCH_SERVICE);
    }

    @Test
    @DisplayName("To graph with node strategy (temporary test)")
    void toGraph() {
        assertGeneratedContentContains("toGraph/default", Set.of(CUSTOMER_NODE),
                // In mapper
                " recordToGraphType(List<CustomerRecord> _mi_customerRecord, NodeIdStrategy _iv_nodeIdStrategy, String _iv_path, RecordTransformer _iv_transform)",
                "customerNode.setId(_iv_nodeIdStrategy.createId(_nit_customerRecord, \"CustomerNode\", Customer.CUSTOMER.getPrimaryKey().getFieldsArray()))",

                // In record transformer
                "customerNodeRecordToGraphType(List<CustomerRecord> _mi_input, NodeIdStrategy _iv_nodeIdStrategy, String _iv_path)",
                "customerNodeRecordToGraphType(CustomerRecord _mi_input, NodeIdStrategy _iv_nodeIdStrategy, String _iv_path)",
                "customerNodeRecordToGraphType(List.of(_mi_input), _iv_nodeIdStrategy, _iv_path)",
                ".recordToGraphType(_mi_input, _iv_nodeIdStrategy, _iv_path, this)"
        );
    }

    @Test
    @DisplayName("Custom node ID field to graph with node strategy (temporary test)")
    void toGraphCustomNodeId() {
        assertGeneratedContentContains("toGraph/toGraphCustomNodeId", Set.of(NODE),
                "customerNode.setId(_iv_nodeIdStrategy.createId(_nit_customerRecord, \"C\", Customer.CUSTOMER.CUSTOMER_ID))"
        );
    }

    @Test
    @DisplayName("To graph with node strategy (temporary test)")
    void toGraphNotNodeId() {
        assertGeneratedContentContains("toGraph/notNodeId",
                "customer.setCustomerId(_nit_customerRecord.getCustomerId()"
        );
    }

    @Test
    @DisplayName("To record with node strategy (temporary test)")
    void toRecord() {
        assertGeneratedContentContains("toRecord/default", Set.of(CUSTOMER_NODE),
                // In mapper
                "toJOOQRecord(List<CustomerInputTable> _mi_customerInputTable, NodeIdStrategy _iv_nodeIdStrategy, String _iv_path, RecordTransformer _iv_transform)",
                "nodeIdStrategy.setId(_mo_customerRecord, _nit_customerInputTable.getId(), \"CustomerNode\", Customer.CUSTOMER.getPrimaryKey().getFieldsArray())",

                // In recordtransformer
                "customerInputTableToJOOQRecord(List<CustomerInputTable> _mi_input, NodeIdStrategy _iv_nodeIdStrategy, String _iv_path)",
                "customerInputTableToJOOQRecord(CustomerInputTable _mi_input, NodeIdStrategy _iv_nodeIdStrategy, String _iv_path)",
                "customerInputTableToJOOQRecord(List.of(_mi_input), _iv_nodeIdStrategy, _iv_path)",
                ".toJOOQRecord(_mi_input, _iv_nodeIdStrategy, _iv_path, this)"
        );
    }

    @Test
    @DisplayName("Node ID in jooq record with implicit reference")
    void toRecordWithImplicitReference() {
        assertGeneratedContentContains("toRecord/withImplicitReference", Set.of(CUSTOMER_NODE),
                "nodeIdStrategy.setReferenceId(_mo_customerRecord, _nit_customerInputTable.getAddressId(), \"Address\", Customer.CUSTOMER.ADDRESS_ID)"
        );
    }

    @Test
    @DisplayName("Node ID in jooq record with key reference")
    void toRecordWithReferenceKey() {
        assertGeneratedContentContains("toRecord/withReferenceKey", Set.of(CUSTOMER_NODE),
                "nodeIdStrategy.setReferenceId(_mo_customerRecord, _nit_customerInputTable.getAddressId(), \"Address\", Customer.CUSTOMER.ADDRESS_ID)"
        );
    }

    @Test
    @DisplayName("Wrapper field containing listed Java record with splitQuery field")
    void listedFieldWithJavaRecord() {
        assertGeneratedContentContains(
                "toGraph/splitQueryInNestedJavaRecord", Set.of(NODE),
                "customerJavaRecordToGraphType(_mi_payloadRecord, _iv_nodeIdStrategy, _iv_pathHere + \"customerJavaRecords\")"
        );
    }
}
