package no.fellesstudentsystem.graphitron_newtestorder.code;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static no.fellesstudentsystem.graphitron_newtestorder.TestConfiguration.COMMON_SCHEMA_NAME;
import static no.fellesstudentsystem.graphitron_newtestorder.TestConfiguration.SRC_ROOT;

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
        checkFoundNames("tograph/serviceWithoutOuterRecord", "edit", "customer");
    }
}
