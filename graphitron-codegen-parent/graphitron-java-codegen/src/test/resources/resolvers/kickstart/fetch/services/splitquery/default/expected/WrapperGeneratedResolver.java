package fake.code.generated.resolvers.query;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.api.WrapperResolver;
import fake.graphql.example.model.Customer;
import fake.graphql.example.model.Wrapper;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.util.concurrent.CompletableFuture;
import no.sikt.graphitron.codereferences.services.ResolverFetchService;
import no.sikt.graphql.helpers.resolvers.ServiceDataFetcherHelper;

public class WrapperGeneratedResolver implements WrapperResolver {
    @Override
    public CompletableFuture<Customer> query(Wrapper wrapper, DataFetchingEnvironment env) throws
            Exception {
        var transform = new RecordTransformer(env);
        var resolverFetchService = new ResolverFetchService(transform.getCtx());
        return new ServiceDataFetcherHelper<>(transform).load(
                wrapper.getQueryKey(),
                (resolverKeys) -> resolverFetchService.query(resolverKeys),
                (recordTransform, response) -> recordTransform.customerRecordToGraphType(response, ""));
    }
}
