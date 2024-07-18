package no.fellesstudentsystem.graphitron_newtestorder.code;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static no.fellesstudentsystem.graphitron_newtestorder.TestConfiguration.COMMON_SCHEMA_NAME;

@DisplayName("ProcessedSchema - Can find mutation fields that can be used for transformer generation")
public class TransformerDetectionMutationTest extends AbstractTransformerDetectionTest {
    public TransformerDetectionMutationTest() {
        super(AbstractTransformerDetectionTest.TEST_PATH + "mutation/", TEST_PATH + COMMON_SCHEMA_NAME);
    }

    @Test
    @DisplayName("Java records in mutation services")
    public void serviceWithJavaRecordOutputs() {
        checkFoundNames(
                "tograph/serviceWithJavaRecords",
                "historicalAddresses",
                "customer",
                "historicalAddressesWrapped",
                "city",
                "cityRecord"
        );
    }

    @Test
    @DisplayName("jOQQ records in mutation services")
    public void serviceWithJOOQRecordOutputs() {
        checkFoundNames(
                "tograph/serviceWithJOOQRecords",
                "historicalAddresses",
                "customer",
                "historicalAddressesWrapped",
                "city",
                "cityRecord" // TODO: These last two should not be found.
        );
    }

    @Test
    @DisplayName("Java record inputs in mutation queries")
    public void serviceWithJavaRecordInputs() {
        checkFoundNames("torecord/serviceWithJavaRecords", "address", "serviceResolver", "cityInput", "city", "city");
    }

    @Test
    @DisplayName("jOOQ record inputs in mutation queries")
    public void serviceWithJOOQRecordInputs() {
        checkFoundNames("torecord/serviceWithJOOQRecords", "address", "serviceResolver", "cityInput", "city", "city");
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
