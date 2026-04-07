package no.sikt.graphitron.rewrite.test;

import no.sikt.graphitron.rewrite.test.generated.rewrite.resolvers.LanguageFilmsSource;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;

import java.util.List;

import static no.sikt.graphitron.rewrite.test.jooq.tables.Language.LANGUAGE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the generated {@code LanguageFilmsSource.toSourceRows} method.
 *
 * <p>Given a list of Language records (carrying {@code language_id} values), the method returns
 * one {@code Record2<Integer, Integer>} per source row, where the first value is the 1-based row
 * index and the second is the language ID. No database connection is needed.
 */
class LanguageFilmsSourceTest {

    private static final org.jooq.DSLContext CTX = DSL.using(SQLDialect.DEFAULT);

    private static Record languageRecord(long languageId) {
        return CTX.newRecord(LANGUAGE.LANGUAGE_ID).values(languageId);
    }

    @Test
    void singleSource_returnsOneRowWithIndex1() {
        List<Record> sources = List.of(languageRecord(2L));

        var rows = LanguageFilmsSource.toSourceRows(CTX, sources);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).value1()).isEqualTo(1);   // 1-based index
        assertThat(rows.get(0).value2()).isEqualTo(2L);  // language_id
    }

    @Test
    void multipleSources_preservesOrderAndAssignsConsecutiveIndex() {
        List<Record> sources = List.of(
            languageRecord(3L),
            languageRecord(1L),
            languageRecord(2L)
        );

        var rows = LanguageFilmsSource.toSourceRows(CTX, sources);

        assertThat(rows).hasSize(3);
        assertThat(rows.get(0).value1()).isEqualTo(1);
        assertThat(rows.get(0).value2()).isEqualTo(3L);
        assertThat(rows.get(1).value1()).isEqualTo(2);
        assertThat(rows.get(1).value2()).isEqualTo(1L);
        assertThat(rows.get(2).value1()).isEqualTo(3);
        assertThat(rows.get(2).value2()).isEqualTo(2L);
    }

    @Test
    void emptySources_returnsEmptyList() {
        var rows = LanguageFilmsSource.toSourceRows(CTX, List.of());

        assertThat(rows).isEmpty();
    }
}
