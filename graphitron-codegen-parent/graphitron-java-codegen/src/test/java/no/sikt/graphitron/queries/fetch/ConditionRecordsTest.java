package no.sikt.graphitron.queries.fetch;

import no.sikt.graphitron.common.GeneratorTest;
import no.sikt.graphitron.configuration.externalreferences.ExternalReference;
import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.generators.db.fetch.FetchDBClassGenerator;
import no.sikt.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.common.configuration.ReferencedEntry.*;
import static no.sikt.graphitron.common.configuration.SchemaComponent.*;

@DisplayName("Fetch condition queries - Queries that apply custom conditions with records")
public class ConditionRecordsTest extends GeneratorTest {
    @Override
    protected String getSubpath() {
        return "queries/fetch/records";
    }

    @Override
    protected Set<ExternalReference> getExternalReferences() {
        return makeReferences(
                RECORD_FETCH_CONDITION,
                RECORD_FETCH_STAFF_CONDITION,
                DUMMY_RECORD,
                JAVA_RECORD_STAFF_INPUT1,
                JAVA_RECORD_STAFF_INPUT2,
                JAVA_RECORD_STAFF_INPUT3,
                JAVA_RECORD_STAFF_NAME
        );
    }

    @Override
    protected List<ClassGenerator> makeGenerators(ProcessedSchema schema) {
        return List.of(new FetchDBClassGenerator(schema));
    }

    @Test
    @DisplayName("Conditions with input Java records")
    void withInputJavaRecordAndCondition() {
        assertGeneratedContentMatches("withInputJavaRecordAndCondition", CUSTOMER_TABLE);
    }

    @Test
    @DisplayName("Conditions with input jOOQ records")
    void withInputJOOQRecordAndCondition() {
        assertGeneratedContentMatches("withInputJOOQRecordAndCondition", CUSTOMER_TABLE);
    }

    @Test
    @DisplayName("Conditions with listed inputs")
    void withListedInputConditions() {
        assertGeneratedContentMatches("withListedInputConditions", CUSTOMER_TABLE);
    }

    @Test
    @DisplayName("Condition with nested input records")
    void withNestedConditionRecord() {
        assertGeneratedContentMatches("withNestedConditionRecord", CUSTOMER_TABLE);
    }

    @Test
    @DisplayName("Condition with input record and pagination")
    void withPaginatedConditionAndRecord() {
        assertGeneratedContentMatches("withPaginatedConditionAndRecord", CUSTOMER_CONNECTION, CUSTOMER_INPUT_TABLE);
    }

    @Test
    @DisplayName("Condition on parameter of Java record input type")
    void inputJavaRecordCondition() {
        assertGeneratedContentContains(
                "inputJavaRecordCondition", Set.of(STAFF, NAME_INPUT_JAVA),
                "_staff.FIRST_NAME.eq(nameRecord.getFirstName())",
                "_staff.LAST_NAME.eq(nameRecord.getLastName())",
                "_staff.ACTIVE.eq(active)",
                ".name(_staff, nameRecord))"
        );
    }

    @Test
    @DisplayName("Condition on listed parameter of Java record input type and field")
    void listInputJavaRecordAndFieldCondition() {
        assertGeneratedContentContains(
                "listInputJavaRecordAndFieldCondition", Set.of(STAFF, NAME_INPUT_JAVA),
                "_staff.ACTIVE.eq(active)",
                "_staff.FIRST_NAME,_staff.LAST_NAME).in(namesRecordList.stream().map(internal_it_ ->" +
                        "DSL.row(DSL.inline(internal_it_.getFirstName()),DSL.inline(internal_it_.getLastName())",
                ".nameList(_staff, namesRecordList)",
                ".fieldWithListInput(_staff, namesRecordList, active)"
        );
    }

    @Test
    @DisplayName("Condition on listed parameter of Java record input type and override condition on field")
    void listInputJavaRecordAndFieldOverrideCondition() {
        assertGeneratedContentContains(
                "listInputJavaRecordAndFieldOverrideCondition", Set.of(STAFF, NAME_INPUT_JAVA),
                ".where(no.sikt.graphitron.codereferences.conditions.RecordStaffCondition.nameList(_staff, namesRecordList))" +
                ".and(no.sikt.graphitron.codereferences.conditions.RecordStaffCondition.fieldWithListInput(_staff, namesRecordList, active))" +
                ".orderBy"
        );
    }

    @Test
    @DisplayName("Override condition on listed parameter of Java record input type containing another input list")
    void nestedListInputJavaRecordOverrideCondition() {
        assertGeneratedContentContains(
                "nestedListInputJavaRecordOverrideCondition", Set.of(STAFF, NAME_INPUT_JAVA),
                ".where(no.sikt.graphitron.codereferences.conditions.RecordStaffCondition.input1(_staff, inputs1RecordList))" +
                ".orderBy"
        );
    }

    @Test
    @DisplayName("Overriding condition on parameter of Java record input type contained within a list and multiple levels of input types")
    void multiLevelInputJavaRecordOverrideCondition() {
        assertGeneratedContentContains(
                "multiLevelInputJavaRecordOverrideCondition", Set.of(STAFF, NAME_INPUT_JAVA),
                ".where(" +
                        "input3Record.getInputs2() != null && input3Record.getInputs2().size() > 0 ?" +
                        "DSL.row(DSL.trueCondition()).in(" +
                                "input3Record.getInputs2().stream().map(internal_it_ ->" +
                                        "DSL.row(no.sikt.graphitron.codereferences.conditions.RecordStaffCondition.input1(_staff, internal_it_.getInput1()))" +
                                ").toList()" +
                        ") : DSL.noCondition()" +
                ")" +
                ".orderBy"
        );
    }
}
