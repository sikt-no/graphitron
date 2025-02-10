package fake.code.generated.resolvers.mutation;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.api.MutationMutationResolver;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.lang.String;
import java.util.concurrent.CompletableFuture;
import no.sikt.graphitron.codereferences.services.ResolverMutationService;

public class MutationGeneratedResolver implements MutationMutationResolver {
    @Override
    public CompletableFuture<String> mutation(DataFetchingEnvironment env) throws
            Exception {
        var transform = new RecordTransformer(env);
        var resolverMutationService = new ResolverMutationService(transform.getCtx());
        var mutation = resolverMutationService.mutation();
        return CompletableFuture.completedFuture(mutation);
    }
}
