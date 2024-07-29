package no.fellesstudentsystem.graphitron_newtestorder.code;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static no.fellesstudentsystem.graphitron_newtestorder.TestComponent.*;

@DisplayName("ProcessedSchema - Can find fields that can be used for transformer generation")
public class TransformerDetectionTest extends AbstractTransformerDetectionTest {
    public TransformerDetectionTest() {
        super(AbstractTransformerDetectionTest.TEST_PATH);
    }

    @Test
    @DisplayName("Filters duplicate uses of a record")
    public void filtersDuplicates() {
        checkFoundNames("filtersDuplicates", Set.of(DUMMY_TYPE_RECORD), "query");
    }

    @Test
    @DisplayName("Filters duplicate uses of a record across mutations and queries")
    public void filtersDuplicatesForQueryAndMutation() {
        checkFoundNames("filtersDuplicatesForQueryAndMutation", Set.of(DUMMY_TYPE_RECORD, DUMMY_INPUT_RECORD, CUSTOMER_TABLE, CUSTOMER_INPUT_TABLE), "queryJava", "inQueryJava", "queryJOOQ", "inQueryJOOQ");
    }
}
