package fake.code.generated.resolvers.mutation;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.api.MutationMutationResolver;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.lang.String;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import no.fellesstudentsystem.graphitron_newtestorder.codereferences.services.ResolverMutationService;
import org.jooq.DSLContext;

public class MutationGeneratedResolver implements MutationMutationResolver {
    @Inject
    DSLContext ctx;

    @Override
    public CompletableFuture<String> mutation(DataFetchingEnvironment env) throws
            Exception {
        var transform = new RecordTransformer(env, this.ctx);
        var resolverMutationService = new ResolverMutationService(transform.getCtx());
        var mutation = resolverMutationService.mutation();
        return CompletableFuture.completedFuture(mutation);
    }
}
