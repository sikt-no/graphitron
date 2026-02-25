package no.sikt.graphql.exception;

import org.jooq.exception.DataAccessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataAccessMatcherTest {

    private static DataAccessException createExceptionWithSqlCause(int errorCode, String sqlState, String message) {
        var sqlException = new SQLException(message, sqlState, errorCode);
        return new DataAccessException(message, sqlException);
    }

    @Test
    @DisplayName("matches on error code only")
    void matchesOnErrorCode() {
        var matcher = new DataAccessMatcher("1", null, null);
        assertTrue(matcher.matches(createExceptionWithSqlCause(1, "23000", "some message")));
    }

    @Test
    @DisplayName("does not match when error code differs")
    void notMatchesWrongErrorCode() {
        var matcher = new DataAccessMatcher("1", null, null);
        assertFalse(matcher.matches(createExceptionWithSqlCause(2, "23000", "some message")));
    }

    @Test
    @DisplayName("matches on sqlState only")
    void matchesOnSqlState() {
        var matcher = new DataAccessMatcher(null, "23503", null);
        assertTrue(matcher.matches(createExceptionWithSqlCause(0, "23503", "some message")));
    }

    @Test
    @DisplayName("does not match when sqlState differs")
    void notMatchesWrongSqlState() {
        var matcher = new DataAccessMatcher(null, "23503", null);
        assertFalse(matcher.matches(createExceptionWithSqlCause(0, "23514", "some message")));
    }

    @Test
    @DisplayName("matches on both error code and sqlState")
    void matchesOnBothCodeAndSqlState() {
        var matcher = new DataAccessMatcher("1", "23000", null);
        assertTrue(matcher.matches(createExceptionWithSqlCause(1, "23000", "some message")));
    }

    @Test
    @DisplayName("does not match when code matches but sqlState differs")
    void notMatchesCodeMatchesSqlStateDiffers() {
        var matcher = new DataAccessMatcher("1", "23503", null);
        assertFalse(matcher.matches(createExceptionWithSqlCause(1, "23000", "some message")));
    }

    @Test
    @DisplayName("matches on sqlState with message substring")
    void matchesOnSqlStateWithMessage() {
        var matcher = new DataAccessMatcher(null, "23514", "year_check");
        assertTrue(matcher.matches(createExceptionWithSqlCause(0, "23514", "violates constraint year_check")));
    }

    @Test
    @DisplayName("does not match when sqlState matches but message does not")
    void notMatchesSqlStateMatchesMessageDiffers() {
        var matcher = new DataAccessMatcher(null, "23514", "year_check");
        assertFalse(matcher.matches(createExceptionWithSqlCause(0, "23514", "violates constraint other_check")));
    }

    @Test
    @DisplayName("matches when neither code nor sqlState is specified")
    void matchesWhenNeitherCodeNorSqlState() {
        var matcher = new DataAccessMatcher(null, null, null);
        assertTrue(matcher.matches(createExceptionWithSqlCause(0, "23503", "some message")));
    }

    @Test
    @DisplayName("does not match when no SQL cause exists")
    void notMatchesNoSqlCause() {
        var matcher = new DataAccessMatcher(null, "23503", null);
        assertFalse(matcher.matches(new DataAccessException("no sql cause")));
    }
}
