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
                "customer_2952383337_address.DISTRICT",
                ".leftJoin(customer_2952383337_address_left",
                ".where(customer_2952383337_address_left.DISTRICT.eq(district"
        );
    }
}
