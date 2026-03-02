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
    public static DataFetcher<CompletableFuture<ConnectionImpl<CustomerTable>>> customers() {
        return _iv_env -> {
            Integer _mi_first = _iv_env.getArgument("first");
            String _mi_after = _iv_env.getArgument("after");
            int _iv_pageSize = ResolverHelpers.getPageSize(_mi_first, 1000, 100);
            var _iv_transform = new RecordTransformer(_iv_env);
            var _rs_resolverFetchService = new ResolverFetchService(_iv_transform.getCtx());
            return new ServiceDataFetcherHelper<>(_iv_transform).loadPaginated(
                    _iv_pageSize,
                    () -> _rs_resolverFetchService.queryList(_iv_pageSize, _mi_after),
                    (_iv_keys) -> _rs_resolverFetchService.countQueryList(),
                    (_iv_recordTransform, _iv_response) -> _iv_recordTransform.customerTableRecordToGraphType(_iv_response, "")
            );
        } ;
    }
}
