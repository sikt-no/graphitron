package no.sikt.graphql.exception;

import graphql.execution.ExecutionStepInfo;
import graphql.execution.ResultPath;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.GraphQLFieldDefinition;
import org.jooq.exception.DataAccessException;
import org.jooq.exception.IntegrityConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.BindException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class ExceptionStrategyTest {

    private static final String MUTATION1_NAME = "mutation1";
    private static final String MUTATION_1_PAYLOAD = "mutation1Payload";

    @Mock
    private DataFetchingEnvironment mockEnvironment;
    @Mock
    private GraphQLFieldDefinition mockFieldDefinition;

    private SchemaBasedErrorStrategy underTest;

    private final DataAccessExceptionMapper dataAccessExceptionMapper = new DataAccessExceptionMapper() {
        @Override
        public String getMsgFromException(DataAccessException exception) {
            return "configured exception message";
        }
    };

    @BeforeEach
    void setUp() {
        var exceptionToErrorMappingProvider = new TestExceptionToErrorMappingProvider();
        underTest = new TestSchemaBasedErrorStrategy(
                new TestMutationExceptionStrategyConfiguration(),
                exceptionToErrorMappingProvider,
                dataAccessExceptionMapper
        );
        when(mockEnvironment.getFieldDefinition()).thenReturn(mockFieldDefinition);
        when(mockFieldDefinition.getName()).thenReturn(MUTATION1_NAME);
    }

    @Test
    @DisplayName("handleException returns payload when ValidationViolationGraphQLException should be handled")
    void shouldReturnPayloadWhenValidationViolationGraphQLExceptionShouldBeHandled() throws ExecutionException, InterruptedException {
        Throwable thrownException = new ValidationViolationGraphQLException(List.of());

        assertThat(underTest.handleException(mockEnvironment, thrownException).orElseThrow().get()).isEqualTo(MUTATION_1_PAYLOAD);
    }

    @Test
    @DisplayName("handleException returns payload when IllegalArgumentException should be handled")
    void shouldReturnPayloadWhenIllegalArgumentExceptionShouldBeHandled() throws ExecutionException, InterruptedException {
        var executionStepInfo = mock(ExecutionStepInfo.class);
        when(executionStepInfo.getPath()).thenReturn(mock(ResultPath.class));
        when(mockEnvironment.getExecutionStepInfo()).thenReturn(executionStepInfo);
        Throwable thrownException = new IllegalArgumentException();

        assertThat(underTest.handleException(mockEnvironment, thrownException).orElseThrow().get()).isEqualTo(MUTATION_1_PAYLOAD);
    }

    @Test
    @DisplayName("handleException returns payload when DataAccessException should be handled")
    void shouldReturnPayloadWhenDataAccessExceptionShouldBeHandled() throws ExecutionException, InterruptedException {
        Throwable thrownException = new DataAccessException("error");

        assertThat(underTest.handleException(mockEnvironment, thrownException).orElseThrow().get()).isEqualTo(MUTATION_1_PAYLOAD);
    }

    @Test
    @DisplayName("handleException returns payload when exception extending DataAccessException should be handled")
    void shouldReturnPayloadWhenExtensionOfDataAccessExceptionShouldBeHandled() throws ExecutionException, InterruptedException {
        Throwable thrownException = new IntegrityConstraintViolationException("error"); //IntegrityConstraintViolationException extends DataAccessException

        assertThat(underTest.handleException(mockEnvironment, thrownException).orElseThrow().get()).isEqualTo(MUTATION_1_PAYLOAD);
    }

    @Test
    @DisplayName("handleException returns payload when some generic exception matches")
    void shouldReturnPayloadWhenSomeGenericExceptionMatches() throws ExecutionException, InterruptedException {
        Throwable thrownException = new BindException("the exception must contain substring of message to be handled");

        assertThat(underTest.handleException(mockEnvironment, thrownException).orElseThrow().get()).isEqualTo(MUTATION_1_PAYLOAD);
    }

    @Test
    @DisplayName("handleException returns empty when the exception message does not match")
    void shouldReturnEmptyWhenTheExceptionMessageDoesNotMatch() {
        Throwable thrownException = new BindException("wrong message");

        assertThat(underTest.handleException(mockEnvironment, thrownException)).isEmpty();
    }

    @Test
    @DisplayName("handleException returns empty when the exception type does not match")
    void shouldReturnEmptyWhenTheExceptionTypeDoesNotMatch() {
        Throwable thrownException = new ArithmeticException("some message");

        assertThat(underTest.handleException(mockEnvironment, thrownException)).isEmpty();
    }

    @Test
    @DisplayName("handleException returns empty when the mutation is not configured for the exception type")
    void shouldReturnEmptyWhenTheMutationIsNotConfiguredForTheExceptionType() {
        when(mockFieldDefinition.getName()).thenReturn("unconfigured_mutation");
        Throwable thrownException = new ValidationViolationGraphQLException(List.of());

        assertThat(underTest.handleException(mockEnvironment, thrownException)).isEmpty();
    }

    private static class TestSchemaBasedErrorStrategy extends SchemaBasedErrorStrategy {

        public TestSchemaBasedErrorStrategy(
                ExceptionStrategyConfiguration configuration,
                ExceptionToErrorMappingProvider mappingProvider,
                DataAccessExceptionMapper dataAccessMapper) {
            super(configuration, mappingProvider, dataAccessMapper);
        }

        @Override
        public Optional<CompletableFuture<Object>> handleValidationException(
                ValidationViolationGraphQLException e,
                String operationName) {
            return Optional.of(CompletableFuture.completedFuture(MUTATION_1_PAYLOAD));
        }

        @Override
        public Optional<CompletableFuture<Object>> handleIllegalArgumentException(
                IllegalArgumentException e,
                String operationName,
                ResultPath path) {
            return Optional.of(CompletableFuture.completedFuture(MUTATION_1_PAYLOAD));
        }

        @Override
        protected Object createDefaultDataAccessError(String operationName, String message) {
            return new SomeError(List.of(operationName), message);
        }

        @Override
        protected Optional<CompletableFuture<Object>> createPayload(String operationName, List<?> errors) {
            // For testing, just return the constant payload
            return Optional.of(CompletableFuture.completedFuture(MUTATION_1_PAYLOAD));
        }
    }

    // Test configuration classes
    private static class TestMutationExceptionStrategyConfiguration implements ExceptionStrategyConfiguration {
        @Override
        public Map<Class<? extends Throwable>, Set<String>> getFieldsForException() {
            Map<Class<? extends Throwable>, Set<String>> fieldMapping = new HashMap<>();
            fieldMapping.put(ValidationViolationGraphQLException.class, Set.of(MUTATION1_NAME));
            fieldMapping.put(IllegalArgumentException.class, Set.of(MUTATION1_NAME));
            fieldMapping.put(DataAccessException.class, Set.of(MUTATION1_NAME));
            fieldMapping.put(BindException.class, Set.of(MUTATION1_NAME));
            return fieldMapping;
        }

        @Override
        public Map<String, PayloadCreator> getPayloadForField() {
            Map<String, PayloadCreator> payloadMapping = new HashMap<>();
            payloadMapping.put(MUTATION1_NAME, errors -> MUTATION_1_PAYLOAD);
            return payloadMapping;
        }
    }

    private static class TestExceptionToErrorMappingProvider implements ExceptionToErrorMappingProvider {
        private final Map<String, List<DataAccessExceptionContentToErrorMapping>> dataAccessMappingsForOperation;
        private final Map<String, List<GenericExceptionContentToErrorMapping>> genericMappingsForOperation;

        public TestExceptionToErrorMappingProvider() {
            dataAccessMappingsForOperation = new HashMap<>();
            genericMappingsForOperation = new HashMap<>();

            var m1 = new DataAccessExceptionContentToErrorMapping(
                    new DataAccessMatcher("20997", null),
                    (path, msg) -> new SomeError(path, "This is an error"));

            var mutation1DatabaseList = List.of(m1);

            var m2 = new GenericExceptionContentToErrorMapping(
                    new GenericExceptionMatcher("java.lang.ArithmeticException", null),
                    (path, msg) -> new SomeError(path, "This is an error"));
            var m3 = new GenericExceptionContentToErrorMapping(
                    new GenericExceptionMatcher("java.net.BindException", "substring of message"),
                    SomeError::new);

            var mutation1GenericList = List.of(m2, m3);

            dataAccessMappingsForOperation.put(MUTATION1_NAME, mutation1DatabaseList);
            genericMappingsForOperation.put(MUTATION1_NAME, mutation1GenericList);
        }

        @Override
        public Map<String, List<DataAccessExceptionContentToErrorMapping>> getDataAccessMappingsForOperation() {
            return dataAccessMappingsForOperation;
        }

        @Override
        public Map<String, List<GenericExceptionContentToErrorMapping>> getGenericMappingsForOperation() {
            return genericMappingsForOperation;
        }
    }

    // Simple error class for testing
    private record SomeError(List<String> path, String msg) {
    }
}