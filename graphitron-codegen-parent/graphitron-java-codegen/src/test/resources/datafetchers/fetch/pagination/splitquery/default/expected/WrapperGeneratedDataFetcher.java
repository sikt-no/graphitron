package fake.code.generated.resolvers.query;

import fake.code.generated.queries.WrapperDBQueries;
import fake.graphql.example.model.DummyType;
import fake.graphql.example.model.Wrapper;
import graphql.schema.DataFetcher;
import java.lang.Integer;
import java.lang.String;
import java.util.concurrent.CompletableFuture;

import no.sikt.graphql.helpers.resolvers.DataFetcherHelper;
import no.sikt.graphql.helpers.resolvers.ResolverHelpers;
import no.sikt.graphql.relay.ConnectionImpl;

public class WrapperGeneratedDataFetcher {
    public static DataFetcher<CompletableFuture<ConnectionImpl<DummyType>>> query() {
        return _iv_env -> {
            Wrapper _os_wrapper = _iv_env.getSource();
            Integer _mi_first = _iv_env.getArgument("first");
            String _mi_after = _iv_env.getArgument("after");
            int _iv_pageSize = ResolverHelpers.getPageSize(_mi_first, 1000, 100);
            return new DataFetcherHelper(_iv_env).loadPaginated(
                    _os_wrapper.getQueryKey(), _iv_pageSize,
                    (_iv_ctx, _iv_keys, _iv_selectionSet) -> WrapperDBQueries.queryForWrapper(_iv_ctx, _iv_keys, _iv_pageSize, _mi_after, _iv_selectionSet),
                    (_iv_ctx, _iv_keys) -> WrapperDBQueries.countQueryForWrapper(_iv_ctx, _iv_keys)
            );
        };
    }
}
