package no.sikt.graphitron.validation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static no.sikt.graphitron.common.configuration.SchemaComponent.CUSTOMER_CONNECTION;
import static no.sikt.graphitron.common.configuration.SchemaComponent.CUSTOMER_TABLE;

@DisplayName("Schema validation - splitQuery fields after services returning Java record")
public class SplitQueryAfterJavaServiceTest extends ValidationTest {
    @Override
    protected String getSubpath() {
        return super.getSubpath() + "service/java";
    }

    @Test
    @DisplayName("Single object should not throw error")
    void defaultCase() {
        getProcessedSchema("default", Set.of(CUSTOMER_TABLE));
    }

    @Test
    @DisplayName("Listed field is not currently supported")
    void listed() {
        assertErrorsContain("listed", Set.of(CUSTOMER_TABLE),
                "'DummyTypeRecord.customer' in a java record has splitQuery directive, " +
                        "but is listed. This is not currently supported."
        );
    }

    @Test
    @DisplayName("Paginated field is not currently supported")
    void paginated() {
        assertErrorsContain("paginated", Set.of(CUSTOMER_CONNECTION),
                "'DummyTypeRecord.customer' in a java record has splitQuery directive, " +
                        "but is paginated. This is not supported.");
    }

    @Test
    @DisplayName("Does not return type")
    void notType() {
        assertErrorsContain("notType", Set.of(CUSTOMER_TABLE),
                "'DummyTypeRecord.customer' in a java record has splitQuery directive, " +
                        "but does not return a type with table. This is not supported.");
    }

    @Test
    @DisplayName("Type without table")
    void typeWithoutTable() {
        assertErrorsContain("typeWithoutTable",
                "'DummyTypeRecord.wrapper' in a java record has splitQuery directive, " +
                        "but does not return a type with table. This is not supported."
        );
    }
}
