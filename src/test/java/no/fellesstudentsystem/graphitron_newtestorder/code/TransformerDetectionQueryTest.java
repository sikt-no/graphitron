package no.fellesstudentsystem.graphitron_newtestorder.code;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static no.fellesstudentsystem.graphitron_newtestorder.TestConfiguration.COMMON_SCHEMA_NAME;
import static no.fellesstudentsystem.graphitron_newtestorder.TestConfiguration.SRC_ROOT;

@DisplayName("ProcessedSchema - Can find query fields that can be used for transformer generation")
public class TransformerDetectionQueryTest extends AbstractTransformerDetectionTest {
    public TransformerDetectionQueryTest() {
        super(AbstractTransformerDetectionTest.TEST_PATH + "query/", SRC_ROOT + "/" + COMMON_SCHEMA_NAME);
    }

    @Test
    @DisplayName("Java records in fetch services")
    public void serviceWithJavaRecordOutputs() {
        checkFoundNames(
                "tograph/serviceWithJavaRecords",
                "historicalAddresses",
                "customerQuery",
                "historicalAddressesWrapped",
                "city",
                "cityRecord"
        );
    }

    @Test
    @DisplayName("jOQQ records in fetch services")
    public void serviceWithJOOQRecordOutputs() {
        checkFoundNames(
                "tograph/serviceWithJOOQRecords",
                "historicalAddresses",
                "customerQuery",
                "historicalAddressesWrapped",
                "cityRecord"
        );
    }

    @Test
    @DisplayName("Java records in fetch queries with conditions")
    public void conditionAndJavaRecords() {
        checkFoundNames("torecord/conditionAndJavaRecords", "cityInput", "city", "city");
    }

    @Test
    @DisplayName("jOOQ records in fetch queries with conditions")
    public void conditionAndJOOQRecords() {
        checkFoundNames("torecord/conditionAndJOOQRecords", "cityInput", "city");
    }

    @Test
    @DisplayName("Records in non-root fetch queries with conditions")
    public void conditionRecordsOnSplitQuery() {
        checkFoundNames("conditionRecordsOnSplitQuery", "input2", "input1", "address1");
    }

    @Test
    @DisplayName("Java record inputs in fetch services")
    public void serviceWithJavaRecordInputs() {
        checkFoundNames("torecord/serviceWithJavaRecords", "cityInput", "city", "city");
    }

    @Test
    @DisplayName("jOOQ record inputs in fetch services")
    public void serviceWithJOOQRecordInputs() {
        checkFoundNames("torecord/serviceWithJOOQRecords", "cityInput", "city");
    }

    @Test
    @DisplayName("Records in non-root fetch services")
    public void serviceRecordsOnSplitQuery() {
        checkFoundNames("serviceRecordsOnSplitQuery", "input2", "input1", "address1", "address2");
    }

    @Test
    @DisplayName("Records in fetch services with pagination")
    public void serviceWithPagination() {
        checkFoundNames("tograph/serviceWithPagination", "customerQueryConnection");
    }

    @Test
    @DisplayName("Service without record on first Graph output type")
    public void serviceWithoutTopLevelRecord() {
        checkFoundNames("tograph/serviceWithoutTopLevelRecord", "customerQuery", "customers");
    }

    @Test
    @DisplayName("Conditions without records finds no records")
    public void conditionWithoutRecordClasses() {
        checkFoundNames("conditionWithoutRecordClasses");
    }

    @Test
    @DisplayName("Query with jOOQ record and no conditions or services")
    public void withoutServiceOrCondition() {
        checkFoundNames("torecord/withoutServiceOrCondition", "in1", "in2");
    }

    @Test
    @DisplayName("Query with jOOQ record and no conditions or services")
    public void withoutServiceOrConditionSplitQuery() {
        checkFoundNames("torecord/withoutServiceOrConditionSplitQuery", "in1", "in2");
    }

    @Test
    @DisplayName("Finds records when middle input has no record set")
    public void middleInputWithoutRecord() {
        checkFoundNames("torecord/middleInputWithoutRecord", "level3A", "level3B", "level1");
    }

    @Test
    @DisplayName("Finds records when middle output has no record set")
    public void middleOutputWithoutRecord() {
        checkFoundNames("tograph/middleOutputWithoutRecord", "level3A", "level3B", "query");
    }
}
