package no.sikt.graphitron.rewrite.model;

import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R358 Phase 2: {@link TableRef#sameTable(String)} / {@link TableRef#denotesSameTableAs(TableRef)}
 * are the canonical case-insensitive table-identity comparison. {@code tableName()} stays the
 * case-preserved verbatim {@code @table(name:)} echo, so the same logical table can carry the
 * Oracle-style UPPERCASE {@code @table} casing on one ref and the lowercase jOOQ
 * {@code Table.getName()} casing on another; the predicate must match across that divergence and
 * stay null-safe.
 */
@UnitTier
class TableRefSameTablePredicateTest {

    /** Only {@code tableName} participates in the predicate; the rest is irrelevant here. */
    private static TableRef ref(String tableName) {
        return new TableRef(tableName, null, null, null, null, List.of());
    }

    @Test
    void sameTable_matchesIgnoringCase() {
        var film = ref("FILM"); // verbatim Oracle-style @table casing
        assertThat(film.sameTable("FILM")).isTrue();
        assertThat(film.sameTable("film")).isTrue(); // lowercase jOOQ casing
        assertThat(film.sameTable("FiLm")).isTrue();
        assertThat(film.sameTable("actor")).isFalse();
    }

    @Test
    void sameTable_nullArgIsNotThisTable() {
        assertThat(ref("film").sameTable(null)).isFalse();
    }

    @Test
    void denotesSameTableAs_matchesIgnoringCaseBothDirections() {
        var upper = ref("FILM");
        var lower = ref("film");
        assertThat(upper.denotesSameTableAs(lower)).isTrue();
        assertThat(lower.denotesSameTableAs(upper)).isTrue();
        assertThat(upper.denotesSameTableAs(ref("actor"))).isFalse();
    }

    @Test
    void denotesSameTableAs_nullArgIsNotThisTable() {
        assertThat(ref("film").denotesSameTableAs(null)).isFalse();
    }
}
