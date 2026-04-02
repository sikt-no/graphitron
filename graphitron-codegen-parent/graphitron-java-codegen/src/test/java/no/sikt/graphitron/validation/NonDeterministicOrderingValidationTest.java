package no.sikt.graphitron.validation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Non-deterministic ordering validation - Fields on tables without PK and without @defaultOrder should warn")
public class NonDeterministicOrderingValidationTest extends ValidationTest {
    @Override
    protected String getSubpath() {
        return super.getSubpath() + "nonDeterministicOrdering/";
    }

    @Test
    @DisplayName("Field on table without PK and without @defaultOrder should warn about non-deterministic ordering")
    void fieldWithoutDefaultOrderOnPKlessTable() {
        getProcessedSchema("withoutDefaultOrder");
        assertWarningsContain(
                "has no @defaultOrder directive",
                "FILM_LIST",
                "non-deterministic ordering"
        );
    }

    @Test
    @DisplayName("Field on table without PK with @defaultOrder should not warn")
    void fieldWithDefaultOrderOnPKlessTable() {
        getProcessedSchema("withDefaultOrder");
        assertNoWarnings();
    }

    @Test
    @DisplayName("Field on table without PK with non-nullable @orderBy should not warn")
    void fieldWithNonNullableOrderByOnPKlessTable() {
        getProcessedSchema("withNonNullableOrderBy");
        assertNoWarnings();
    }

    @Test
    @DisplayName("Field on table with primary key should not warn")
    void fieldWithPrimaryKey() {
        getProcessedSchema("withPrimaryKey");
        assertNoWarnings();
    }
}
