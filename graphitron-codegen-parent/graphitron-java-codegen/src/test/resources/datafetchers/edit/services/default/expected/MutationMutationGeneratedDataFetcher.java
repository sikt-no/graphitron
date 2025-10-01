package fake.code.generated.resolvers.mutation;

import fake.code.generated.transform.RecordTransformer;
import graphql.schema.DataFetcher;
import java.lang.String;
import java.util.concurrent.CompletableFuture;
import no.sikt.graphitron.codereferences.services.ResolverMutationService;
import no.sikt.graphql.helpers.resolvers.ServiceDataFetcherHelper;

public class MutationMutationGeneratedDataFetcher {
    public static DataFetcher<CompletableFuture<String>> mutation() {
        return env -> {
            var transform = new RecordTransformer(env);
            var resolverMutationService = new ResolverMutationService(transform.getCtx());
            return new ServiceDataFetcherHelper<>(transform).load(() -> resolverMutationService.mutation());
        };
    }
}
