package no.sikt.graphitron.code;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static no.sikt.graphitron.common.configuration.SchemaComponent.*;

@DisplayName("ProcessedSchema - Can find query fields that can be used for transformer generation")
public class TransformerDetectionQueryTest extends AbstractTransformerDetectionTest {
    @Override
    protected String getSubpath() {
        return super.getSubpath() + "/query";
    }

    @Test
    @DisplayName("Java records in fetch services")
    public void serviceWithJavaRecordOutputs() {
        checkFoundNames(
                "tograph/serviceWithJavaRecords",
                Set.of(WRAPPED_ADDRESS_JAVA),
                "addresses",
                "query",
                "addressesWrapped",
                "city",
                "cityRecord"
        );
    }

    @Test
    @DisplayName("jOQQ records in fetch services")
    public void serviceWithJOOQRecordOutputs() {
        checkFoundNames(
                "tograph/serviceWithJOOQRecords",
                Set.of(WRAPPED_ADDRESS_JOOQ),
                "addresses",
                "query",
                "addressesWrapped",
                "cityRecord"
        );
    }

    @Test
    @DisplayName("Java records in fetch queries with conditions")
    public void conditionAndJavaRecords() {
        checkFoundNames("torecord/conditionAndJavaRecords", Set.of(CITY_INPUTS_JAVA), "cityInput", "city", "city");
    }

    @Test
    @DisplayName("jOOQ records in fetch queries with conditions")
    public void conditionAndJOOQRecords() {
        checkFoundNames("torecord/conditionAndJOOQRecords", Set.of(CITY_INPUTS_JOOQ), "cityInput", "city");
    }

    @Test
    @DisplayName("Records in non-root fetch queries with conditions")
    public void conditionRecordsOnSplitQuery() {
        checkFoundNames("conditionRecordsOnSplitQuery", Set.of(SPLIT_QUERY_WRAPPER), "input2", "input1", "address1");
    }

    @Test
    @DisplayName("Java record inputs in fetch services")
    public void serviceWithJavaRecordInputs() {
        checkFoundNames("torecord/serviceWithJavaRecords", Set.of(CITY_INPUTS_JAVA), "cityInput", "city", "city");
    }

    @Test
    @DisplayName("jOOQ record inputs in fetch services")
    public void serviceWithJOOQRecordInputs() {
        checkFoundNames("torecord/serviceWithJOOQRecords", Set.of(CITY_INPUTS_JOOQ), "cityInput", "city");
    }

    @Test
    @DisplayName("Records in non-root fetch services")
    public void serviceRecordsOnSplitQuery() {
        checkFoundNames(
                "serviceRecordsOnSplitQuery",
                Set.of(SPLIT_QUERY_WRAPPER, DUMMY_TYPE_RECORD, DUMMY_INPUT_RECORD, CUSTOMER_TABLE, CUSTOMER_INPUT_TABLE),
                "query1", "input1", "query2", "input2"
        );
    }

    @Test
    @DisplayName("Records in fetch services with pagination")
    public void serviceWithPagination() {
        checkFoundNames("tograph/serviceWithPagination", "queryConnection");
    }

    @Test
    @DisplayName("Service without record on first Graph output type")
    public void serviceWithoutTopLevelRecord() {
        checkFoundNames("tograph/serviceWithoutTopLevelRecord", "query", "customers");
    }

    @Test
    @DisplayName("Conditions without records finds no records")
    public void conditionWithoutRecordClasses() {
        checkFoundNames("conditionWithoutRecordClasses");
    }

    @Test
    @DisplayName("Query with jOOQ record and no conditions or services")
    public void withoutServiceOrCondition() {
        checkFoundNames("torecord/withoutServiceOrCondition", Set.of(DUMMY_TYPE, DUMMY_INPUT_RECORD, CUSTOMER_INPUT_TABLE), "in2", "in1");
    }

    @Test
    @DisplayName("Query with jOOQ record and no conditions or services")
    public void withoutServiceOrConditionSplitQuery() {
        checkFoundNames("torecord/withoutServiceOrConditionSplitQuery", Set.of(SPLIT_QUERY_WRAPPER, DUMMY_TYPE, DUMMY_INPUT_RECORD, CUSTOMER_INPUT_TABLE), "in2", "in1");
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
