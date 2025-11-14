package fake.code.generated.resolvers.query;

import fake.code.generated.queries.QueryDBQueries;
import fake.graphql.example.model.DummyType;
import graphql.schema.DataFetcher;
import java.lang.Integer;
import java.lang.String;
import java.util.concurrent.CompletableFuture;

import no.sikt.graphql.helpers.resolvers.DataFetcherHelper;
import no.sikt.graphql.helpers.resolvers.ResolverHelpers;
import no.sikt.graphql.relay.ConnectionImpl;

public class QueryGeneratedDataFetcher {
    public static DataFetcher<CompletableFuture<ConnectionImpl<DummyType>>> query() {
        return _iv_env -> {
            Integer _mi_first = _iv_env.getArgument("first");
            String _mi_after = _iv_env.getArgument("after");
            int _iv_pageSize = ResolverHelpers.getPageSize(_mi_first, 1000, 100);
            return new DataFetcherHelper(_iv_env).loadPaginated(
                    _iv_pageSize,
                    (_iv_ctx, _iv_selectionSet) -> QueryDBQueries.queryForQuery(_iv_ctx, _iv_pageSize, _mi_after, _iv_selectionSet),
                    (_iv_ctx, _iv_keys) -> QueryDBQueries.countQueryForQuery(_iv_ctx)
            );
        };
    }
}
