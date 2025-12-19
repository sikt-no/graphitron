package no.sikt.graphitron.validation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Pagination ordering validation - Paginated fields on tables without PK require ordering")
public class PaginationOrderingValidationTest extends ValidationTest {
    @Override
    protected String getSubpath() {
        return super.getSubpath() + "paginationOrdering/";
    }

    @Test
    @DisplayName("Paginated field on table without PK and without ordering should fail validation")
    void paginatedWithoutOrderingOnPKlessTable() {
        assertErrorsContain("withoutOrdering",
                "requires @defaultOrder or @orderBy directive",
                "table 'FILM_LIST' has no primary key"
        );
    }

    @Test
    @DisplayName("Paginated field on table without PK with @defaultOrder should pass validation")
    void paginatedWithDefaultOrderOnPKlessTable() {
        getProcessedSchema("withDefaultOrder");
    }

    @Test
    @DisplayName("Paginated field on table without PK with @orderBy should pass validation")
    void paginatedWithOrderByOnPKlessTable() {
        getProcessedSchema("withOrderBy");
    }
}
