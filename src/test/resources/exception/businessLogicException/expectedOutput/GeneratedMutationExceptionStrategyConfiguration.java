package fake.code.generated.exception;

import fake.graphql.example.model.EditCustomerWithMultipleErrorPayloads;
import fake.graphql.example.model.EditCustomerWithOtherErrorPayload;
import fake.graphql.example.model.EditCustomerWithUnionErrorPayload;
import fake.graphql.example.model.MyValidationError;
import fake.graphql.example.model.OtherError;
import fake.graphql.example.model.UnionOfErrors;
import java.lang.Class;
import java.lang.Override;
import java.lang.String;
import java.lang.Throwable;
import java.lang.UnsupportedOperationException;
import java.net.BindException;
import java.security.GeneralSecurityException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.inject.Singleton;
import no.fellesstudentsystem.graphql.exception.MutationExceptionStrategyConfiguration;
import org.jooq.exception.DataAccessException;

@Singleton
public class GeneratedMutationExceptionStrategyConfiguration implements MutationExceptionStrategyConfiguration {
    private final Map<Class<? extends Throwable>, Set<String>> mutationsForException;

    private final Map<String, MutationExceptionStrategyConfiguration.PayloadCreator> payloadForMutation;

    public GeneratedMutationExceptionStrategyConfiguration() {
        mutationsForException = new HashMap<>();
        payloadForMutation = new HashMap<>();
        mutationsForException.computeIfAbsent(UnsupportedOperationException.class, k -> new HashSet<>()).add("editCustomerWithMultipleErrors");
        mutationsForException.computeIfAbsent(BindException.class, k -> new HashSet<>()).add("editCustomerWithMultipleErrors");
        mutationsForException.computeIfAbsent(DataAccessException.class, k -> new HashSet<>()).add("editCustomerWithMultipleErrors");
        mutationsForException.computeIfAbsent(GeneralSecurityException.class, k -> new HashSet<>()).add("editCustomerWithMultipleErrors");
        payloadForMutation.put("editCustomerWithMultipleErrors", errors -> {
            var payload = new EditCustomerWithMultipleErrorPayloads();
            payload.setErrors1((List<MyValidationError>) errors);
            payload.setErrors2((List<UnionOfErrors>) errors);
            return payload;
        } );

        mutationsForException.get(UnsupportedOperationException.class).add("editCustomerWithOtherError");
        mutationsForException.get(BindException.class).add("editCustomerWithOtherError");
        mutationsForException.get(DataAccessException.class).add("editCustomerWithOtherError");
        payloadForMutation.put("editCustomerWithOtherError", errors -> {
            var payload = new EditCustomerWithOtherErrorPayload();
            payload.setErrors((List<OtherError>) errors);
            return payload;
        } );

        mutationsForException.get(UnsupportedOperationException.class).add("editCustomerWithUnionError");
        mutationsForException.get(BindException.class).add("editCustomerWithUnionError");
        mutationsForException.get(DataAccessException.class).add("editCustomerWithUnionError");
        mutationsForException.get(GeneralSecurityException.class).add("editCustomerWithUnionError");
        payloadForMutation.put("editCustomerWithUnionError", errors -> {
            var payload = new EditCustomerWithUnionErrorPayload();
            payload.setErrors((List<UnionOfErrors>) errors);
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
