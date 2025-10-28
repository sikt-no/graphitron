package fake.code.generated.resolvers.query;

import fake.code.generated.queries.QueryDBQueries;
import fake.graphql.example.model.DummyType;
import graphql.schema.DataFetcher;
import java.lang.String;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import no.sikt.graphql.helpers.resolvers.DataFetcherHelper;

public class QueryGeneratedDataFetcher {
    public static DataFetcher<CompletableFuture<DummyType>> query() {
        return _iv_env -> {
            List<String> _mi_id = _iv_env.getArgument("id");
            var _iv_lookupKeys = List.of(_mi_id);
            return new DataFetcherHelper(_iv_env).loadLookup(_iv_lookupKeys, (_iv_ctx, _iv_keys, _iv_selectionSet) -> QueryDBQueries.queryForQuery(_iv_ctx, _mi_id, _iv_selectionSet));
        };
    }
}
