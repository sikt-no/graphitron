package fake.code.generated.resolvers.query;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.model.Customer;
import graphql.schema.DataFetcher;
import java.util.concurrent.CompletableFuture;
import no.sikt.graphitron.codereferences.services.ResolverFetchService;
import no.sikt.graphql.helpers.resolvers.ServiceDataFetcherHelper;

public class QueryGeneratedDataFetcher {
    public static DataFetcher<CompletableFuture<Customer>> query() {
        return _iv_env -> {
            var _iv_transform = new RecordTransformer(_iv_env);
            var resolverFetchService = new ResolverFetchService(_iv_transform.getCtx());
            return new ServiceDataFetcherHelper<>(_iv_transform).load(
                    () -> resolverFetchService.query(),
                    (recordTransform, response) -> recordTransform.customerRecordToGraphType(response, ""));
        };
    }
}
