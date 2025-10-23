package fake.code.generated.resolvers.query;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.model.Customer;
import fake.graphql.example.model.Wrapper;
import graphql.schema.DataFetcher;
import java.util.concurrent.CompletableFuture;
import no.sikt.graphitron.codereferences.services.ResolverFetchService;
import no.sikt.graphql.helpers.resolvers.ServiceDataFetcherHelper;

public class WrapperGeneratedDataFetcher {
    public static DataFetcher<CompletableFuture<Customer>> query() {
        return _iv_env -> {
            Wrapper wrapper = _iv_env.getSource();
            var _iv_transform = new RecordTransformer(_iv_env);
            var resolverFetchService = new ResolverFetchService(_iv_transform.getCtx());
            return new ServiceDataFetcherHelper<>(_iv_transform).load(
                    wrapper.getQueryKey(),
                    (_iv_keys) -> resolverFetchService.query(_iv_keys),
                    (recordTransform, response) -> recordTransform.customerRecordToGraphType(response, ""));
        };
    }
}
