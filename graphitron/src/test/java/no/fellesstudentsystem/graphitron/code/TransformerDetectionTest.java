package no.fellesstudentsystem.graphitron.code;

import no.fellesstudentsystem.graphitron.common.configuration.SchemaComponent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static no.fellesstudentsystem.graphitron.common.configuration.SchemaComponent.*;

@DisplayName("ProcessedSchema - Can find fields that can be used for transformer generation")
public class TransformerDetectionTest extends AbstractTransformerDetectionTest {
    @Override
    protected Set<SchemaComponent> getComponents() {
        return makeComponents(DUMMY_TYPE_RECORD);
    }

    @Test
    @DisplayName("Filters duplicate uses of a record")
    public void filtersDuplicates() {
        checkFoundNames("filtersDuplicates", "query");
    }

    @Test
    @DisplayName("Filters duplicate uses of a record across mutations and queries")
    public void filtersDuplicatesForQueryAndMutation() {
        checkFoundNames(
                "filtersDuplicatesForQueryAndMutation",
                Set.of(DUMMY_INPUT_RECORD, CUSTOMER_TABLE, CUSTOMER_INPUT_TABLE),
                "queryJava",
                "inQueryJava",
                "queryJOOQ",
                "inQueryJOOQ"
        );
    }
}
