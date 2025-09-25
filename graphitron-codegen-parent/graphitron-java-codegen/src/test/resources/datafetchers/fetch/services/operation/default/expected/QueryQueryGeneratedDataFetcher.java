package fake.code.generated.resolvers.query;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.model.Customer;
import graphql.schema.DataFetcher;
import java.util.concurrent.CompletableFuture;
import no.sikt.graphitron.codereferences.services.ResolverFetchService;
import no.sikt.graphql.helpers.resolvers.ServiceDataFetcherHelper;

public class QueryQueryGeneratedDataFetcher {
    public static DataFetcher<CompletableFuture<Customer>> query() {
        return env -> {
            var transform = new RecordTransformer(env);
            var resolverFetchService = new ResolverFetchService(transform.getCtx());
            return new ServiceDataFetcherHelper<>(transform).load(
                    () -> resolverFetchService.query(),
                    (recordTransform, response) -> recordTransform.customerRecordToGraphType(response, ""));
        };
    }
}
