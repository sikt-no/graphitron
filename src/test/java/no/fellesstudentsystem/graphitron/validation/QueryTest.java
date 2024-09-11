package no.fellesstudentsystem.graphitron.validation;

import no.fellesstudentsystem.graphql.directives.GenerationDirective;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static no.fellesstudentsystem.graphitron.common.configuration.SchemaComponent.CUSTOMER_CONNECTION;
import static no.fellesstudentsystem.graphitron.common.configuration.SchemaComponent.CUSTOMER_TABLE;
import static no.fellesstudentsystem.graphql.directives.GenerationDirective.LOOKUP_KEY;
import static no.fellesstudentsystem.graphql.directives.GenerationDirective.ORDER_BY;

@DisplayName("Schema validation - Errors thrown when checking the schema")
public class QueryTest extends ValidationTest {
    @Override
    protected String getSubpath() {
        return super.getSubpath() + "query";
    }

    @Test
    @DisplayName("Has connection but misses the pagination inputs")
    void noPaginationFields() {
        assertErrorsContain(
                () -> getProcessedSchema("noPaginationFields", CUSTOMER_CONNECTION),
                "Type CustomerConnection ending with the reserved suffix 'Connection' must have either " +
                        "forward(first and after fields) or backwards(last and before fields) pagination, yet " +
                        "neither was found."
        );
    }

    @Test
    @DisplayName("Has connection but misses some of the pagination inputs")
    void incompletePaginationFields() {
        assertErrorsContain(
                () -> getProcessedSchema("incompletePaginationFields", CUSTOMER_CONNECTION),
                "Type CustomerConnection ending with the reserved suffix 'Connection' must have either " +
                        "forward(first and after fields) or backwards(last and before fields) pagination, yet " +
                        "neither was found."
        );
    }

    @Test
    @DisplayName("Query with lookup keys set")
    void lookupAndOrderBy() {
        assertErrorsContain(
                () -> getProcessedSchema("lookupAndOrderBy", CUSTOMER_TABLE),
                String.format("'query' has both @%s and @%s defined. These directives can not be used together", ORDER_BY.getName(), LOOKUP_KEY.getName())
        );
    }

    @Test
    @DisplayName("Set both lookup keys and pagination")
    void lookupAndPagination() {
        assertErrorsContain(
                () -> getProcessedSchema("lookupAndPagination", CUSTOMER_CONNECTION),
                String.format("'customers' has both pagination and @%s defined. These can not be used together", GenerationDirective.LOOKUP_KEY.getName())
        );
    }

    @Test
    @DisplayName("Type references self but does not create a new query")
    void selfReferenceWithoutSplit() {
        assertErrorsContain("selfReferenceWithoutSplit", "Self reference must have splitQuery, field \"customer\" in object \"Customer\"");
    }

    @Test
    @DisplayName("Implicit join with an incorrect path")
    void impossibleImplicitJoin() {
        getProcessedSchema("impossibleImplicitJoin", Set.of(CUSTOMER_TABLE));
        assertWarningsContain("No field(s) or method(s) with name(s) 'customer' found in table 'FILM'");
    }

    @Test  // Reverse references are allowed and should not cause warnings or errors.
    @DisplayName("Correct reverse join")
    void reverseJoin() {
        getProcessedSchema("reverseJoin", Set.of(CUSTOMER_TABLE));
        assertNoWarnings();
    }
}
