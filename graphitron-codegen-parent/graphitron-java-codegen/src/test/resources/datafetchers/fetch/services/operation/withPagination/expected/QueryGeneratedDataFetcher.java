package fake.code.generated.resolvers.query;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.model.CustomerTable;
import graphql.schema.DataFetcher;
import java.lang.Integer;
import java.lang.String;
import java.util.concurrent.CompletableFuture;

import no.sikt.graphitron.codereferences.services.ResolverFetchService;
import no.sikt.graphql.helpers.resolvers.ResolverHelpers;
import no.sikt.graphql.helpers.resolvers.ServiceDataFetcherHelper;
import no.sikt.graphql.relay.ConnectionImpl;

public class QueryGeneratedDataFetcher {
    public static DataFetcher<CompletableFuture<ConnectionImpl<CustomerTable>>> query() {
        return env -> {
            Integer first = env.getArgument("first");
            String after = env.getArgument("after");
            int pageSize = ResolverHelpers.getPageSize(first, 1000, 100);
            var transform = new RecordTransformer(env);
            var resolverFetchService = new ResolverFetchService(transform.getCtx());
            return new ServiceDataFetcherHelper<>(transform).loadPaginated(
                    pageSize, 1000,
                    () -> resolverFetchService.queryList(pageSize, after),
                    (resolverKeys) -> resolverFetchService.countQueryList(),
                    (recordTransform, response) -> recordTransform.customerTableRecordToGraphType(response, "")
            );
        };
    }
}
