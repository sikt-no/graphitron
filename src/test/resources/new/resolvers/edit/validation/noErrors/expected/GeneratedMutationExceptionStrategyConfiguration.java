package fake.code.generated.resolvers.mutation;

import java.lang.Class;
import java.lang.Override;
import java.lang.String;
import java.lang.Throwable;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import javax.inject.Singleton;
import no.fellesstudentsystem.graphql.exception.MutationExceptionStrategyConfiguration;

@Singleton
public class GeneratedMutationExceptionStrategyConfiguration implements MutationExceptionStrategyConfiguration {
    private final Map<Class<? extends Throwable>, Set<String>> mutationsForException;

    private final Map<String, MutationExceptionStrategyConfiguration.PayloadCreator> payloadForMutation;

    public GeneratedMutationExceptionStrategyConfiguration() {
        mutationsForException = new HashMap<>();
        payloadForMutation = new HashMap<>();
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
