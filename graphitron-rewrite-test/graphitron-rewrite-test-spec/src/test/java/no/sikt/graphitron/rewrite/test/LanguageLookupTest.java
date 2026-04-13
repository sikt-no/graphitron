package no.sikt.graphitron.rewrite.test;

import no.sikt.graphitron.rewrite.test.generated.rewrite.resolvers.LanguageLookup;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the generated {@code LanguageLookup.toInputRows} method, which exercises the
 * input-type-arg code path: a single {@code List<Map<String,Object>>} argument where each
 * element map carries the key fields.
 */
class LanguageLookupTest {

    private static final org.jooq.DSLContext CTX = DSL.using(SQLDialect.DEFAULT);

    @Test
    void singleKey_returnsOneRowWithIndex1() {
        var rows = LanguageLookup.toInputRows(CTX, Map.of("key", List.of(Map.of("languageId", 3))));

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).value1()).isEqualTo(1);  // 1-based index
        assertThat(rows.get(0).value2()).isEqualTo(3);  // language_id
    }

    @Test
    void multipleKeys_preservesOrderAndAssignsConsecutiveIndex() {
        var keys = List.of(Map.of("languageId", 5), Map.of("languageId", 2), Map.of("languageId", 8));
        var rows = LanguageLookup.toInputRows(CTX, Map.of("key", keys));

        assertThat(rows).hasSize(3);
        assertThat(rows.get(0).value1()).isEqualTo(1);
        assertThat(rows.get(0).value2()).isEqualTo(5);
        assertThat(rows.get(1).value1()).isEqualTo(2);
        assertThat(rows.get(1).value2()).isEqualTo(2);
        assertThat(rows.get(2).value1()).isEqualTo(3);
        assertThat(rows.get(2).value2()).isEqualTo(8);
    }

    @Test
    void emptyList_returnsEmptyList() {
        var rows = LanguageLookup.toInputRows(CTX, Map.of("key", List.of()));

        assertThat(rows).isEmpty();
    }
}
