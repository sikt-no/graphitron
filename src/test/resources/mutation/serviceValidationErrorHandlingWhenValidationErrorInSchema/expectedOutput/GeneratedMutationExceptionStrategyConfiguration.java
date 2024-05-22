package fake.code.generated.exception;

import fake.graphql.example.model.ValidationErrorUnion;
import fake.graphql.example.model.EditCustomerResponse;
import fake.graphql.example.model.EditCustomerResponse2;
import fake.graphql.example.model.ValidationErrorAndHandledError;
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

public class GeneratedMutationExceptionStrategyConfiguration implements MutationExceptionStrategyConfiguration {
    private final Map<Class<? extends Throwable>, Set<String>> mutationsForException;

    private final Map<String, MutationExceptionStrategyConfiguration.PayloadCreator> payloadForMutation;

    public GeneratedMutationExceptionStrategyConfiguration() {
        mutationsForException = new HashMap<>();
        payloadForMutation = new HashMap<>();
        mutationsForException.computeIfAbsent(ValidationViolationGraphQLException.class, k -> new HashSet<>()).add("editCustomerInput");
        mutationsForException.computeIfAbsent(IllegalArgumentException.class, k -> new HashSet<>()).add("editCustomerInput");
        payloadForMutation.put("editCustomerInput", errors -> {
            var payload = new EditCustomerResponse();
            payload.setErrors((List<ValidationErrorUnion>) errors);
            return payload;
        } );

        mutationsForException.get(ValidationViolationGraphQLException.class).add("editCustomerInput2");
        mutationsForException.get(IllegalArgumentException.class).add("editCustomerInput2");
        payloadForMutation.put("editCustomerInput2", errors -> {
            var payload = new EditCustomerResponse2();
            payload.setErrors((List<ValidationErrorAndHandledError>) errors);
            return payload;
        } );

    }

    @Override
    public Map<Class<? extends Throwable>, Set<String>> getMutationsForException() {
        return mutationsForException;
    }

    @Override
    public Map<String, MutationExceptionStrategyConfiguration.PayloadCreator> getPayloadForMutation(
    ) {
        return payloadForMutation;
    }
}