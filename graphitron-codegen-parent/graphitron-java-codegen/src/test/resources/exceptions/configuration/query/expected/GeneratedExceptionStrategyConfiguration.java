package fake.code.generated.resolvers.mutation;

import fake.graphql.example.model.ValidationError;
import fake.graphql.example.model.Response;
import java.lang.Class;
import java.lang.IllegalArgumentException;
import java.lang.Override;
import java.lang.String;
import java.lang.Throwable;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import no.sikt.graphql.exception.ExceptionStrategyConfiguration;
import no.sikt.graphql.exception.ValidationViolationGraphQLException;
import javax.inject.Singleton;

@Singleton
public class GeneratedExceptionStrategyConfiguration implements ExceptionStrategyConfiguration {
    private final Map<Class<? extends Throwable>, Set<String>> fieldsForException;

    private final Map<String, ExceptionStrategyConfiguration.PayloadCreator> payloadForField;

    public GeneratedExceptionStrategyConfiguration() {
        fieldsForException = new HashMap<>();
        payloadForField = new HashMap<>();
        fieldsForException.computeIfAbsent(ValidationViolationGraphQLException.class, k -> new HashSet<>()).add("query");
        fieldsForException.computeIfAbsent(IllegalArgumentException.class, k -> new HashSet<>()).add("query");
        payloadForField.put("query", errors -> {
            var payload = new Response();
            payload.setErrors((List<ValidationError>) errors);
            return payload;
        } );

    }

    @Override
    public Map<Class<? extends Throwable>, Set<String>> getFieldsForException() {
        return fieldsForException;
    }

    @Override
    public Map<String, ExceptionStrategyConfiguration.PayloadCreator> getPayloadForField() {
        return payloadForField;
    }
}
