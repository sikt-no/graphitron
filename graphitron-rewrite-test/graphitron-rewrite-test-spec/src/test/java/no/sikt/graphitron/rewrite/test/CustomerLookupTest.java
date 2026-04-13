package no.sikt.graphitron.rewrite.test;

import no.sikt.graphitron.rewrite.test.generated.rewrite.resolvers.CustomerLookup;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the generated {@code CustomerLookup.toInputRows} method, which exercises the flat
 * multi-arg code path: one list argument ({@code customer_id}) that drives row cardinality, and
 * one scalar argument ({@code store_id}) that is broadcast to every row.
 */
class CustomerLookupTest {

    private static final org.jooq.DSLContext CTX = DSL.using(SQLDialect.DEFAULT);

    @Test
    void scalarArgIsBroadcastToAllRows() {
        var rows = CustomerLookup.toInputRows(CTX, Map.of(
            "customer_id", List.of(10, 20, 30),
            "store_id", 2
        ));

        assertThat(rows).hasSize(3);
        assertThat(rows.get(0).value2()).isEqualTo(10);
        assertThat(rows.get(1).value2()).isEqualTo(20);
        assertThat(rows.get(2).value2()).isEqualTo(30);
        // store_id is the same on every row
        assertThat(rows).allSatisfy(r -> assertThat(r.value3()).isEqualTo(2));
    }

    @Test
    void indexIsOneBasedAndPreservesOrder() {
        var rows = CustomerLookup.toInputRows(CTX, Map.of(
            "customer_id", List.of(100, 200),
            "store_id", 1
        ));

        assertThat(rows.get(0).value1()).isEqualTo(1);
        assertThat(rows.get(1).value1()).isEqualTo(2);
    }

    @Test
    void emptyList_returnsEmptyList() {
        var rows = CustomerLookup.toInputRows(CTX, Map.of(
            "customer_id", List.of(),
            "store_id", 1
        ));

        assertThat(rows).isEmpty();
    }
}
