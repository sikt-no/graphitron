package no.sikt.graphql.exception;

import org.jooq.exception.DataAccessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

class ErrorMessageFormatterTest {

    private ErrorMessageFormatter formatter;

    @BeforeEach
    void setUp() {
        formatter = new ErrorMessageFormatter();
    }

    @Test
    @DisplayName("formats unique constraint violation")
    void shouldFormatUniqueConstraintViolation() {
        SQLException sqlException = new SQLException(
                "ERROR: duplicate key value violates unique constraint \"user_email_key\"",
                "23505"
        );
        DataAccessException exception = new DataAccessException("Database error", sqlException);

        String result = formatter.formatDataAccessException(exception);

        assertThat(result).isEqualTo("Duplicate value not allowed");
    }

    @Test
    @DisplayName("formats foreign key constraint violation")
    void shouldFormatForeignKeyViolation() {
        SQLException sqlException = new SQLException(
                "ERROR: violates foreign key constraint",
                "23503"
        );
        DataAccessException exception = new DataAccessException("Database error", sqlException);

        String result = formatter.formatDataAccessException(exception);

        assertThat(result).isEqualTo("Referenced record does not exist");
    }

    @Test
    @DisplayName("formats not null constraint violation")
    void shouldFormatNotNullViolation() {
        SQLException sqlException = new SQLException(
                "ERROR: null value in column violates not-null constraint",
                "23502"
        );
        DataAccessException exception = new DataAccessException("Database error", sqlException);

        String result = formatter.formatDataAccessException(exception);

        assertThat(result).isEqualTo("Required field cannot be empty");
    }

    @Test
    @DisplayName("formats check constraint violation with constraint name")
    void shouldFormatCheckConstraintViolation() {
        SQLException sqlException = new SQLException(
                "ERROR: new row for relation \"film\" violates check constraint \"year_check\"",
                "23514"
        );
        DataAccessException exception = new DataAccessException("Database error", sqlException);

        String result = formatter.formatDataAccessException(exception);

        assertThat(result).isEqualTo("Value violates constraint: year");
    }

    @Test
    @DisplayName("formats check constraint violation without constraint name")
    void shouldFormatCheckConstraintViolationWithoutName() {
        SQLException sqlException = new SQLException(
                "ERROR: violates check constraint",
                "23514"
        );
        DataAccessException exception = new DataAccessException("Database error", sqlException);

        String result = formatter.formatDataAccessException(exception);

        assertThat(result).isEqualTo("Value does not meet validation requirements");
    }

    @Test
    @DisplayName("formats data too long error")
    void shouldFormatDataTooLong() {
        SQLException sqlException = new SQLException(
                "ERROR: value too long for type character varying(100)",
                "22001"
        );
        DataAccessException exception = new DataAccessException("Database error", sqlException);

        String result = formatter.formatDataAccessException(exception);

        assertThat(result).isEqualTo("Value exceeds maximum length");
    }

    @Test
    @DisplayName("cleans up generic SQL error messages")
    void shouldCleanupGenericSqlError() {
        SQLException sqlException = new SQLException(
                "ERROR:   Something    went   wrong",
                "99999"
        );
        DataAccessException exception = new DataAccessException("Database error", sqlException);

        String result = formatter.formatDataAccessException(exception);

        assertThat(result).isEqualTo("Something went wrong");
    }

    @Test
    @DisplayName("handles DataAccessException without SQL cause")
    void shouldHandleExceptionWithoutSqlCause() {
        DataAccessException exception = new DataAccessException("Connection timeout");

        String result = formatter.formatDataAccessException(exception);

        assertThat(result).isEqualTo("Connection timeout");
    }

    @Test
    @DisplayName("handles SQLException with null message")
    void shouldHandleSqlExceptionWithNullMessage() {
        SQLException sqlException = new SQLException(null, "23505");
        DataAccessException exception = new DataAccessException("Database error", sqlException);

        String result = formatter.formatDataAccessException(exception);

        assertThat(result).isEqualTo("Database error");
    }

    @Test
    @DisplayName("handles exception with blank message")
    void shouldHandleExceptionWithBlankMessage() {
        DataAccessException exception = new DataAccessException("   ");

        String result = formatter.formatDataAccessException(exception);

        assertThat(result).isEqualTo("Database operation failed");
    }
}