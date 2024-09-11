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
import no.fellesstudentsystem.graphql.exception.MutationExceptionStrategyConfiguration;
import no.fellesstudentsystem.graphql.exception.ValidationViolationGraphQLException;
import javax.inject.Singleton;

@Singleton
public class GeneratedMutationExceptionStrategyConfiguration implements MutationExceptionStrategyConfiguration {
    private final Map<Class<? extends Throwable>, Set<String>> mutationsForException;

    private final Map<String, MutationExceptionStrategyConfiguration.PayloadCreator> payloadForMutation;

    public GeneratedMutationExceptionStrategyConfiguration() {
        mutationsForException = new HashMap<>();
        payloadForMutation = new HashMap<>();
        mutationsForException.computeIfAbsent(ValidationViolationGraphQLException.class, k -> new HashSet<>()).add("mutation");
        mutationsForException.computeIfAbsent(IllegalArgumentException.class, k -> new HashSet<>()).add("mutation");
        payloadForMutation.put("mutation", errors -> {
            var payload = new Response();
            payload.setErrors((List<ValidationError>) errors);
            return payload;
        });
    }

    @Override
    public Map<Class<? extends Throwable>, Set<String>> getMutationsForException() {
        return mutationsForException;
    }

    @Override
    public Map<String, MutationExceptionStrategyConfiguration.PayloadCreator> getPayloadForMutation() {
        return payloadForMutation;
    }
}
