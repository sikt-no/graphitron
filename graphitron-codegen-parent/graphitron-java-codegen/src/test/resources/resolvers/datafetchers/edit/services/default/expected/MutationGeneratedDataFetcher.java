package fake.code.generated.resolvers.mutation;

import fake.code.generated.transform.RecordTransformer;
import graphql.schema.DataFetcher;
import java.lang.String;
import java.util.concurrent.CompletableFuture;
import no.sikt.graphitron.codereferences.services.ResolverMutationService;

public class MutationGeneratedDataFetcher {
    public static DataFetcher<CompletableFuture<String>> mutation() {
        return env -> {
            var transform = new RecordTransformer(env);
            var resolverMutationService = new ResolverMutationService(transform.getCtx());
            var mutation = resolverMutationService.mutation();
            return CompletableFuture.completedFuture(mutation);
        };
    }
}
