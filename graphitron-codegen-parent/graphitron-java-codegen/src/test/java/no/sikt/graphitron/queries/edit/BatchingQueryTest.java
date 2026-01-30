package no.sikt.graphitron.queries.edit;

import no.sikt.graphitron.common.GeneratorTest;
import no.sikt.graphitron.common.configuration.SchemaComponent;
import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.reducedgenerators.UpdateOnlyDBClassGenerator;
import no.sikt.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.common.configuration.SchemaComponent.CUSTOMER_INPUT_TABLE;
import static no.sikt.graphitron.common.configuration.SchemaComponent.NODE;

@DisplayName("Mutation queries - Queries for updating data with JDBC batching")
public class BatchingQueryTest extends GeneratorTest {
    @Override
    protected String getSubpath() {
        return "queries/edit/withBatching";
    }

    @Override
    protected Set<SchemaComponent> getComponents() {
        return makeComponents(CUSTOMER_INPUT_TABLE);
    }

    @Override
    protected List<ClassGenerator> makeGenerators(ProcessedSchema schema) {
        return List.of(new UpdateOnlyDBClassGenerator(schema));
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
    @DisplayName("Update") // This is default for no particular reason, could have been any of the types.
    void defaultCase() {
        assertGeneratedContentMatches("default", NODE);
    }

    @Test
    @DisplayName("Delete")
    void delete() {
        assertGeneratedContentContains("delete", ".batchDelete(_mi_inRecord)");
    }

    @Test
    @DisplayName("Insert")
    void insert() {
        assertGeneratedContentContains("insert", ".batchInsert(_mi_inRecord)");
    }

    @Test
    @DisplayName("Upsert")
    void upsert() {
        assertGeneratedContentContains("upsert", ".batchMerge(_mi_inRecord)");
    }

    @Test
    @DisplayName("Upsert with node id")
    void upsertNodeId() {
        assertGeneratedContentContains("upsertNodeId", Set.of(NODE),
                "_mi_inRecord.changed(VACATION_DESTINATION.DESTINATION_ID, true);",
                "inRecord.changed(VACATION_DESTINATION.COUNTRY_NAME, true);",
                ".batchMerge(_mi_inRecord)"
        );
    }

    @Test
    @DisplayName("Upsert with implicit node id")
    void upsertImplicitNodeId() {
        assertGeneratedContentContains("upsertNodeIdWithImplicitTypeName", Set.of(NODE),
                "inRecord.changed(VACATION_DESTINATION.DESTINATION_ID, true);",
                "inRecord.changed(VACATION_DESTINATION.COUNTRY_NAME, true);",
                ".batchMerge(_mi_inRecord)"
        );
    }

    @Test
    @DisplayName("Upsert with node id being an iterable")
    void upsertNodeIdIterable() {
        assertGeneratedContentContains("upsertNodeIdIterable", Set.of(NODE),
                "inRecordList.forEach(_iv_it -> {",
                "it.changed(VACATION_DESTINATION.DESTINATION_ID, true);",
                "it.changed(VACATION_DESTINATION.COUNTRY_NAME, true);",
                ".batchMerge(_mi_inRecordList)"
        );
    }

    @Test
    @DisplayName("Upsert with node id having custom key columns")
    void upsertNodeIdCustomKeyColumns() {
        assertGeneratedContentContains("upsertNodeIdCustomKeyColumns", Set.of(NODE),
                "inRecord.changed(VACATION_DESTINATION.KEY_COLUMN1, true);",
                "inRecord.changed(VACATION_DESTINATION.KEY_COLUMN2, true);",
                ".batchMerge(_mi_inRecord)"
        );
    }

    @Test
    @DisplayName("Mutation with an unusable extra field")
    void extraField() {
        assertGeneratedContentContains("extraField", ", CustomerRecord _mi_inRecord, String _mi_id", ".batchUpdate(_mi_inRecord)");
    }

    @Test
    @DisplayName("List") // Mutation type does not matter.
    void listed() {
        assertGeneratedContentContains("listed", "List<CustomerRecord> _mi_inRecordList", ".batchUpdate(_mi_inRecordList)");
    }

    // These four may be unused in practice and could have problems.
    @Test
    @DisplayName("Double record mutation")
    void twoRecords() {
        assertGeneratedContentMatches("twoRecords", NODE);
    }

    @Test
    @DisplayName("Mutations with two distinct records")
    void twoDifferentRecords() {
        assertGeneratedContentContains("twoDifferentRecords", ", CustomerRecord _mi_in1Record, AddressRecord _mi_in2Record");
    }

    @Test
    @DisplayName("Mutations with two records where both are lists")
    void twoListedRecords() {
        assertGeneratedContentContains(
                "twoListedRecords",
                ", List<CustomerRecord> _mi_in1RecordList, List<CustomerRecord> _mi_in2RecordList",
                "recordList.addAll(_mi_in1RecordList)",
                "recordList.addAll(_mi_in2RecordList)"
        );
    }

    @Test
    @DisplayName("Mutations with two records where one is a list")
    void twoRecordsOneListed() {
        assertGeneratedContentContains(
                "twoRecordsOneListed",
                ", List<CustomerRecord> _mi_in1RecordList, CustomerRecord _mi_in2Record",
                "recordList.add(_mi_in2Record)",
                "recordList.addAll(_mi_in1RecordList)"
        );
    }
}
