package no.fellesstudentsystem.graphitron_newtestorder.code;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static no.fellesstudentsystem.graphitron_newtestorder.TestConfiguration.COMMON_SCHEMA_NAME;
import static no.fellesstudentsystem.graphitron_newtestorder.TestConfiguration.SRC_ROOT;

@DisplayName("ProcessedSchema - Can find fields that can be used for transformer generation")
public class TransformerDetectionTest extends AbstractTransformerDetectionTest {
    public TransformerDetectionTest() {
        super(AbstractTransformerDetectionTest.TEST_PATH, SRC_ROOT + "/" + COMMON_SCHEMA_NAME);
    }

    @Test
    @DisplayName("Filters duplicate uses of a record")
    public void filtersDuplicates() {
        checkFoundNames("filtersDuplicates", "customer");
    }
}
