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

@DisplayName("Mutation queries - Queries for updating data")
public class QueryTest extends GeneratorTest {
    @Override
    protected String getSubpath() {
        return "queries/edit";
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
        assertGeneratedContentMatches("default");
    }

    @Test
    @DisplayName("Delete")
    void delete() {
        assertGeneratedContentContains("delete", ".batchDelete(inRecord)");
    }

    @Test
    @DisplayName("Insert")
    void insert() {
        assertGeneratedContentContains("insert", ".batchInsert(inRecord)");
    }

    @Test
    @DisplayName("Upsert")
    void upsert() {
        assertGeneratedContentContains("upsert", ".batchMerge(inRecord)");
    }

    @Test
    @DisplayName("Upsert with node id")
    void upsertNodeId() {
        assertGeneratedContentContains("upsertNodeId",
                "inRecord.changed(VACATION_DESTINATION.DESTINATION_ID, true);",
                "inRecord.changed(VACATION_DESTINATION.COUNTRY_NAME, true);",
                ".batchMerge(inRecord)"
        );
    }

    @Test
    @DisplayName("Upsert with implicit node id")
    void upsertImplicitNodeId() {
        assertGeneratedContentContains("upsertImplicitNodeId",
                "inRecord.changed(VACATION_DESTINATION.DESTINATION_ID, true);",
                "inRecord.changed(VACATION_DESTINATION.COUNTRY_NAME, true);",
                ".batchMerge(inRecord)"
        );
    }

    @Test
    @DisplayName("Upsert with node id being an iterable")
    void upsertNodeIdIterable() {
        assertGeneratedContentContains("upsertNodeIdIterable",
                "inRecordList.forEach(_iv_it -> {",
                "it.changed(VACATION_DESTINATION.DESTINATION_ID, true);",
                "it.changed(VACATION_DESTINATION.COUNTRY_NAME, true);",
                ".batchMerge(inRecordList)"
        );
    }

    @Test
    @DisplayName("Upsert with node id having custom key columns")
    void upsertNodeIdCustomKeyColumns() {
        assertGeneratedContentContains("upsertNodeIdCustomKeyColumns",
                "inRecord.changed(VACATION_DESTINATION.KEY_COLUMN1, true);",
                "inRecord.changed(VACATION_DESTINATION.KEY_COLUMN2, true);",
                ".batchMerge(inRecord)"
        );
    }

    @Test
    @DisplayName("Mutation with an unusable extra field")
    void extraField() {
        assertGeneratedContentContains("extraField", ", CustomerRecord inRecord, String id", ".batchUpdate(inRecord)");
    }

    @Test
    @DisplayName("List") // Mutation type does not matter.
    void listed() {
        assertGeneratedContentContains("listed", "List<CustomerRecord> inRecordList", ".batchUpdate(inRecordList)");
    }

    // These four may be unused in practice and could have problems.
    @Test
    @DisplayName("Double record mutation")
    void twoRecords() {
        assertGeneratedContentMatches("twoRecords");
    }

    @Test
    @DisplayName("Mutations with two distinct records")
    void twoDifferentRecords() {
        assertGeneratedContentContains("twoDifferentRecords", ", CustomerRecord in1Record, AddressRecord in2Record");
    }

    @Test
    @DisplayName("Mutations with two records where both are lists")
    void twoListedRecords() {
        assertGeneratedContentContains(
                "twoListedRecords",
                ", List<CustomerRecord> in1RecordList, List<CustomerRecord> in2RecordList",
                "recordList.addAll(in1RecordList)",
                "recordList.addAll(in2RecordList)"
        );
    }

    @Test
    @DisplayName("Mutations with two records where one is a list")
    void twoRecordsOneListed() {
        assertGeneratedContentContains(
                "twoRecordsOneListed",
                ", List<CustomerRecord> in1RecordList, CustomerRecord in2Record",
                "recordList.add(in2Record)",
                "recordList.addAll(in1RecordList)"
        );
    }
}
