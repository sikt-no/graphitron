package no.sikt.graphql.helpers.query;

import no.sikt.graphql.exception.NoRowsAffectedException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("QueryHelper - requireRowsAffected")
class QueryHelperTest {

    @Test
    @DisplayName("Should return the sum when every statement affected a row")
    void shouldSumWhenAllStatementsAffectedRows() {
        assertThat(QueryHelper.requireRowsAffected(new int[]{1, 1, 2})).isEqualTo(4);
    }

    @Test
    @DisplayName("Should pass for a batch that had nothing to write, which reports no counts at all")
    void shouldPassForEmptyCounts() {
        assertThat(QueryHelper.requireRowsAffected(new int[]{})).isZero();
    }

    @Test
    @DisplayName("Should throw when a single statement affected no rows")
    void shouldThrowOnSingleUnaffectedStatement() {
        assertThatThrownBy(() -> QueryHelper.requireRowsAffected(new int[]{0}))
                .isInstanceOf(NoRowsAffectedException.class)
                .hasMessageContaining("1 of 1 statements");
    }

    @Test
    @DisplayName("Should throw when only some statements in a batch affected no rows")
    void shouldThrowOnPartiallyUnaffectedBatch() {
        assertThatThrownBy(() -> QueryHelper.requireRowsAffected(new int[]{1, 0, 1, 0}))
                .isInstanceOf(NoRowsAffectedException.class)
                .hasMessageContaining("2 of 4 statements");
    }

    @Test
    @DisplayName("Should accept counts a driver could not report")
    void shouldAcceptUnreportedCounts() {
        assertThatCode(() -> QueryHelper.requireRowsAffected(
                new int[]{Statement.SUCCESS_NO_INFO, Statement.SUCCESS_NO_INFO}
        )).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Should leave counts a driver could not report out of the sum")
    void shouldNotSumUnreportedCounts() {
        assertThat(QueryHelper.requireRowsAffected(new int[]{1, Statement.SUCCESS_NO_INFO, 1})).isEqualTo(2);
    }

    @Test
    @DisplayName("Should throw when a statement failed outright")
    void shouldThrowOnFailedStatement() {
        assertThatThrownBy(() -> QueryHelper.requireRowsAffected(new int[]{1, Statement.EXECUTE_FAILED}))
                .isInstanceOf(NoRowsAffectedException.class);
    }
}
