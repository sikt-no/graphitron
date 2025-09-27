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

import java.util.List;

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

    private void setupDataFetchingEnvironmentMock() {
        when(mockDataFetchingEnvironment.getField()).thenReturn(mock(Field.class));
        when(mockDataFetchingEnvironment.getExecutionStepInfo()).thenReturn(mock(ExecutionStepInfo.class));
        when(mockHandlerParameters.getDataFetchingEnvironment()).thenReturn(mockDataFetchingEnvironment);
    }
}