package no.sikt.graphitron.validation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static no.sikt.graphitron.common.configuration.SchemaComponent.PAGE_INFO;

@DisplayName("Pagination ordering validation - Paginated fields on tables without PK require ordering")
public class PaginationOrderingValidationTest extends ValidationTest {
    @Override
    protected String getSubpath() {
        return super.getSubpath() + "paginationOrdering/";
    }

    @Test
    @DisplayName("Paginated field on table without PK and without ordering should fail validation")
    void paginatedWithoutOrderingOnPKlessTable() {
        assertErrorsContain("withoutOrdering", Set.of(PAGE_INFO),
                "requires @defaultOrder or @orderBy directive",
                "Table 'FILM_LIST' has no primary key"
        );
    }

    @Test
    @DisplayName("Paginated field on table without PK with @defaultOrder should pass validation")
    void paginatedWithDefaultOrderOnPKlessTable() {
        getProcessedSchema("withDefaultOrder", Set.of(PAGE_INFO));
    }

    @Test
    @DisplayName("Paginated field on table without PK with non-nullable @orderBy should pass validation")
    void paginatedWithNonNullableOrderByOnPKlessTable() {
        getProcessedSchema("withOrderBy", Set.of(PAGE_INFO));
    }

    @Test
    @DisplayName("Paginated field on table without PK with nullable @orderBy but no @defaultOrder should fail validation")
    void paginatedWithNullableOrderByNoDefaultOnPKlessTable() {
        assertErrorsContain("withNullableOrderByNoDefault", Set.of(PAGE_INFO),
                "has nullable @orderBy but no @defaultOrder directive",
                "Table 'FILM_LIST' has no primary key"
        );
    }
}
