package no.sikt.graphitron.rewrite.model;

import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit-tier pin on the {@link OrderBySpec.SortDirection} algebra. The enum carries the
 * jOOQ method-name mapping and a flip helper that emitters and the runtime-flip path rely on;
 * the type system can't constrain either, so this test pins both directly.
 */
@UnitTier
class OrderBySpecSortDirectionTest {

    @Test
    void jooqMethodNameMapsAscToAsc() {
        assertThat(OrderBySpec.SortDirection.ASC.jooqMethodName()).isEqualTo("asc");
    }

    @Test
    void jooqMethodNameMapsDescToDesc() {
        assertThat(OrderBySpec.SortDirection.DESC.jooqMethodName()).isEqualTo("desc");
    }

    @Test
    void flippedAscIsDesc() {
        assertThat(OrderBySpec.SortDirection.ASC.flipped()).isEqualTo(OrderBySpec.SortDirection.DESC);
    }

    @Test
    void flippedDescIsAsc() {
        assertThat(OrderBySpec.SortDirection.DESC.flipped()).isEqualTo(OrderBySpec.SortDirection.ASC);
    }
}
