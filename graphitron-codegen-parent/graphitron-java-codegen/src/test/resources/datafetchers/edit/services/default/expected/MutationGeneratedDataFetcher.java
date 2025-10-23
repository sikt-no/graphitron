package fake.code.generated.resolvers.mutation;

import fake.code.generated.transform.RecordTransformer;
import graphql.schema.DataFetcher;
import java.lang.String;
import java.util.concurrent.CompletableFuture;
import no.sikt.graphitron.codereferences.services.ResolverMutationService;
import no.sikt.graphql.helpers.resolvers.ServiceDataFetcherHelper;

public class MutationGeneratedDataFetcher {
    public static DataFetcher<CompletableFuture<String>> mutation() {
        return _iv_env -> {
            var _iv_transform = new RecordTransformer(_iv_env);
            var resolverMutationService = new ResolverMutationService(_iv_transform.getCtx());
            return new ServiceDataFetcherHelper<>(_iv_transform).load(() -> resolverMutationService.mutation());
        };
    }
}
