package fake.code.generated.resolvers.query;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.model.CustomerTable;
import fake.graphql.example.model.Wrapper;
import graphql.schema.DataFetcher;
import java.lang.Integer;
import java.lang.String;
import java.util.concurrent.CompletableFuture;

import no.sikt.graphitron.codereferences.services.ResolverFetchService;
import no.sikt.graphql.helpers.resolvers.ResolverHelpers;
import no.sikt.graphql.helpers.resolvers.ServiceDataFetcherHelper;
import no.sikt.graphql.relay.ConnectionImpl;

public class WrapperGeneratedDataFetcher {
    public static DataFetcher<CompletableFuture<ConnectionImpl<CustomerTable>>> query() {
        return _iv_env -> {
            Wrapper wrapper = _iv_env.getSource();
            Integer first = _iv_env.getArgument("first");
            String after = _iv_env.getArgument("after");
            int _iv_pageSize = ResolverHelpers.getPageSize(first, 1000, 100);
            var _iv_transform = new RecordTransformer(_iv_env);
            var resolverFetchService = new ResolverFetchService(_iv_transform.getCtx());
            return new ServiceDataFetcherHelper<>(_iv_transform).loadPaginated(
                    wrapper.getQueryKey(), _iv_pageSize,
                    (_iv_keys) -> resolverFetchService.queryMap(_iv_keys, _iv_pageSize, after),
                    (_iv_keys) -> resolverFetchService.countQueryMap(_iv_keys),
                    (recordTransform, response) -> recordTransform.customerTableRecordToGraphType(response, "")
            );
        };
    }
}
