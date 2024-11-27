package no.sikt.graphitron.queries.edit;

import no.sikt.graphitron.common.GeneratorTest;
import no.sikt.graphitron.common.configuration.SchemaComponent;
import no.sikt.graphitron.definitions.interfaces.GenerationTarget;
import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.generators.db.update.UpdateDBClassGenerator;
import no.sikt.graphql.schema.ProcessedSchema;
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
    protected List<ClassGenerator<? extends GenerationTarget>> makeGenerators(ProcessedSchema schema) {
        return List.of(new UpdateDBClassGenerator(schema));
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
