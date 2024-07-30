package no.fellesstudentsystem.graphitron_newtestorder.code;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static no.fellesstudentsystem.graphitron_newtestorder.SchemaComponent.*;

@DisplayName("ProcessedSchema - Can find mutation fields that can be used for transformer generation")
public class TransformerDetectionMutationTest extends AbstractTransformerDetectionTest {
    public TransformerDetectionMutationTest() {
        super(AbstractTransformerDetectionTest.TEST_PATH + "mutation/");
    }

    @Test
    @DisplayName("Java records in mutation services")
    public void serviceWithJavaRecordOutputs() {
        checkFoundNames(
                "tograph/serviceWithJavaRecords",
                Set.of(WRAPPED_ADDRESS_JAVA),
                "addresses",
                "mutation",
                "addressesWrapped",
                "city",
                "cityRecord"
        );
    }

    @Test
    @DisplayName("jOQQ records in mutation services")
    public void serviceWithJOOQRecordOutputs() {
        checkFoundNames(
                "tograph/serviceWithJOOQRecords",
                Set.of(WRAPPED_ADDRESS_JOOQ),
                "addresses",
                "mutation",
                "addressesWrapped",
                "city",
                "cityRecord" // TODO: These last two should not be found.
        );
    }

    @Test
    @DisplayName("Java record inputs in mutation queries")
    public void serviceWithJavaRecordInputs() {
        checkFoundNames("torecord/serviceWithJavaRecords", Set.of(CITY_INPUTS_JAVA), "cityInput", "city", "city");
    }

    @Test
    @DisplayName("jOOQ record inputs in mutation queries")
    public void serviceWithJOOQRecordInputs() {
        checkFoundNames("torecord/serviceWithJOOQRecords", Set.of(CITY_INPUTS_JOOQ), "cityInput", "city", "city");
    }

    @Test
    @DisplayName("Records in non-root fetch services")
    public void serviceRecordsOnSplitQuery() {
        checkFoundNames(
                "serviceRecordsOnSplitQuery",
                Set.of(DUMMY_TYPE_RECORD, DUMMY_INPUT_RECORD, CUSTOMER_TABLE, CUSTOMER_INPUT_TABLE),
                "mutation", "mutation1", "input1", "mutation2", "input2"
        );
    }

    @Test
    @DisplayName("Service without outer record wrapping and fetchByID field")
    public void serviceWithoutRecordOutput() {
        checkFoundNames("tograph/serviceWithoutOuterRecord", "customer1", "edit1", "customer2", "edit2");
    }

    @Test
    @DisplayName("Finds records when middle input has no record set")
    public void middleInputWithoutRecord() {
        checkFoundNames("torecord/middleInputWithoutRecord", "level3A", "level3B", "level1");
    }

    @Test
    @DisplayName("Finds records when middle output has no record set")
    public void middleOutputWithoutRecord() {
        checkFoundNames("tograph/middleOutputWithoutRecord", "level3A", "level3B", "mutation");
    }

    @Test
    @DisplayName("Finds layered jOOQ records") // Allowed for mutation inputs
    public void layeredInputJOOQRecords() {
        checkFoundNames("torecord/layeredInputJOOQRecords", "level3", "level1", "level2");
    }
}
