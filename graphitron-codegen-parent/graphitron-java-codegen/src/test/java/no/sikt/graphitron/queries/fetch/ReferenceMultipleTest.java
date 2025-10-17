package no.sikt.graphitron.queries.fetch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Fetch queries - Multiple references to the same table from different sources")
public class ReferenceMultipleTest extends ReferenceTest {
    @Test
    @DisplayName("From a field and an input")
    void multipleToSameTable() {
        assertGeneratedContentContains(
                "multipleToSameTable",
                "customer_2168032777_address.DISTRICT",
                ".leftJoin(_a_customer_2168032777_address_left",
                ".where(_a_customer_2168032777_address_left.DISTRICT.eq(district"
        );
    }

}
