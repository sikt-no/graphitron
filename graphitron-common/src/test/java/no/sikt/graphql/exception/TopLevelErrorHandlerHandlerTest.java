package no.sikt.graphql.exception;

import graphql.GraphqlErrorBuilder;
import graphql.execution.DataFetcherExceptionHandlerParameters;
import graphql.execution.DataFetcherExceptionHandlerResult;
import graphql.execution.ExecutionStepInfo;
import graphql.language.Field;
import graphql.schema.DataFetchingEnvironment;
import org.jooq.exception.DataAccessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.sql.SQLSyntaxErrorException;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;


@ExtendWith(MockitoExtension.class)
class TopLevelErrorHandlerHandlerTest {

    @Mock
    private DataFetcherExceptionHandlerParameters mockHandlerParameters;
    @Mock
    private DataFetchingEnvironment mockDataFetchingEnvironment;

    private final DataAccessExceptionMapper dataAccessExceptionMapper = new DataAccessExceptionMapper() {
        @Override
        public String getMsgFromException(DataAccessException exception) {
            return "configured exception message";
        }
    };

    @Test
    public void shouldHandleValidationViolationGraphQLException() {
        String exceptionMsg = "Validation violation";
        when(mockHandlerParameters.getException()).thenReturn(new ValidationViolationGraphQLException(List.of(GraphqlErrorBuilder.newError().message(exceptionMsg).build())));

        DataFetcherExceptionHandlerResult result = new TopLevelErrorHandler(dataAccessExceptionMapper).handleException(mockHandlerParameters).join();

        assertThat(result.getErrors()).hasSize(1);
        assertThat(result.getErrors().get(0).getMessage()).isEqualTo(exceptionMsg);
    }

    @Test
    public void shouldHandleIllegalArgumentException() {
        setupDataFetchingEnvironmentMock();

        String exceptionMsg = "Illegal argument";
        when(mockHandlerParameters.getException()).thenReturn(new IllegalArgumentException(exceptionMsg));

        DataFetcherExceptionHandlerResult result = new TopLevelErrorHandler(dataAccessExceptionMapper).handleException(mockHandlerParameters).join();

        assertThat(result.getErrors()).hasSize(1);
        assertThat(result.getErrors().get(0).getMessage()).isEqualTo(exceptionMsg);
    }

    @Test
    public void shouldHandleDataAccessException() {
        setupDataFetchingEnvironmentMock();

        String exceptionMsg = "Database exception";
        when(mockHandlerParameters.getException()).thenReturn(new DataAccessException(exceptionMsg));

        DataFetcherExceptionHandlerResult result = new TopLevelErrorHandler(dataAccessExceptionMapper).handleException(mockHandlerParameters).join();

        assertThat(result.getErrors()).hasSize(1);
        String actualErrorMessage = result.getErrors().get(0).getMessage();
        assertThat(actualErrorMessage).startsWith("An exception occurred. The error has been logged with id ");
        assertThat(actualErrorMessage).endsWith(dataAccessExceptionMapper.getMsgFromException(new DataAccessException(exceptionMsg)));
    }

    @Test
    public void shouldHandleUnrecognizedException() {
        setupDataFetchingEnvironmentMock();

        String exceptionMsg = "Implementation details that should not be exposed";
        when(mockHandlerParameters.getException()).thenReturn(new Exception(exceptionMsg));

        DataFetcherExceptionHandlerResult result = new TopLevelErrorHandler(dataAccessExceptionMapper).handleException(mockHandlerParameters).join();

        assertThat(result.getErrors()).hasSize(1);
        String actualErrorMessage = result.getErrors().get(0).getMessage();
        assertThat(actualErrorMessage).startsWith("An exception occurred. The error has been logged with id ");
        assertThat(actualErrorMessage).doesNotContain(exceptionMsg);
    }

    @Test
    public void shouldReuseIdForTheSameExceptionObject() {
        setupDataFetchingEnvironmentMock();
        var handler = new TopLevelErrorHandler(dataAccessExceptionMapper);

        // A failed DataLoader batch delivers the same exception object to every field in the batch.
        var batchException = new DataAccessException("Database exception");
        when(mockHandlerParameters.getException()).thenReturn(batchException);
        var firstId = extractId(handler.handleException(mockHandlerParameters).join());
        var secondId = extractId(handler.handleException(mockHandlerParameters).join());

        when(mockHandlerParameters.getException()).thenReturn(new DataAccessException("Database exception"));
        var otherId = extractId(handler.handleException(mockHandlerParameters).join());

        assertThat(secondId).isEqualTo(firstId);
        assertThat(otherId).isNotEqualTo(firstId);
    }

    @Test
    public void shouldReuseIdForTheSameUnrecognizedExceptionObject() {
        setupDataFetchingEnvironmentMock();
        var handler = new TopLevelErrorHandler(dataAccessExceptionMapper);

        when(mockHandlerParameters.getException()).thenReturn(new RuntimeException("Failure"));
        var firstId = extractId(handler.handleException(mockHandlerParameters).join());
        var secondId = extractId(handler.handleException(mockHandlerParameters).join());

        assertThat(secondId).isEqualTo(firstId);
    }

    @Test
    public void shouldDescribeDataAccessExceptionWithoutSql() {
        var sql = "select secret_column from secret_table where id in (?, ?, ?)";
        var driverCause = new RuntimeException("Error : 942, Position : 14, SQL = " + sql + ", Original SQL = " + sql);
        var sqlException = new SQLSyntaxErrorException("ORA-00942: table or view does not exist", "42000", 942, driverCause);
        var exception = new DataAccessException("SQL [" + sql + "]; ORA-00942: table or view does not exist", sqlException);

        var description = new TopLevelErrorHandler(dataAccessExceptionMapper).describeDataAccessException(exception);

        assertThat(description)
                .isEqualTo("org.jooq.exception.DataAccessException [SQL state: 42000, vendor code: 942] " +
                        "java.sql.SQLSyntaxErrorException: ORA-00942: table or view does not exist")
                .doesNotContain("secret");
    }

    @Test
    public void shouldTruncateLongDriverMessages() {
        var longMessage = "x".repeat(TopLevelErrorHandler.MAX_LOGGED_MESSAGE_LENGTH + 500);
        var exception = new DataAccessException("SQL [select 1]; " + longMessage, new SQLSyntaxErrorException(longMessage, "42000", 1));

        var description = new TopLevelErrorHandler(dataAccessExceptionMapper).describeDataAccessException(exception);

        assertThat(description)
                .endsWith("x... (truncated, " + longMessage.length() + " characters)")
                .hasSizeLessThan(TopLevelErrorHandler.MAX_LOGGED_MESSAGE_LENGTH + 200);
    }

    @Test
    public void shouldDescribeDataAccessExceptionWithoutDriverException() {
        var description = new TopLevelErrorHandler(dataAccessExceptionMapper)
                .describeDataAccessException(new DataAccessException("No JDBC\nConnection configured"));

        assertThat(description).isEqualTo("org.jooq.exception.DataAccessException: No JDBC Connection configured");
    }

    private static String extractId(DataFetcherExceptionHandlerResult result) {
        var matcher = Pattern.compile("logged with id ([0-9a-f-]{36})").matcher(result.getErrors().get(0).getMessage());
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }

    private void setupDataFetchingEnvironmentMock() {
        when(mockDataFetchingEnvironment.getField()).thenReturn(mock(Field.class));
        when(mockDataFetchingEnvironment.getExecutionStepInfo()).thenReturn(mock(ExecutionStepInfo.class));
        when(mockHandlerParameters.getDataFetchingEnvironment()).thenReturn(mockDataFetchingEnvironment);
    }
}