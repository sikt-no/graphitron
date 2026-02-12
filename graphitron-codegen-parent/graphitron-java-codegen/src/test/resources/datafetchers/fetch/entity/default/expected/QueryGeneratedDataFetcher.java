package fake.code.generated.queries.query;

import fake.code.generated.queries.QueryDBQueries;
import fake.graphql.example.model._Entity;
import graphql.schema.DataFetcher;
import java.lang.Object;
import java.lang.String;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import no.sikt.graphql.helpers.resolvers.DataFetcherHelper;

public class QueryGeneratedDataFetcher {
    public static DataFetcher<CompletableFuture<List<_Entity>>> _entities() {
        return _iv_env -> {
            List<Map<String, Object>> _mi_representations = _iv_env.getArgument("representations");
            return new DataFetcherHelper(_iv_env).load((_iv_ctx, _iv_selectionSet) -> QueryDBQueries._entitiesForQuery(_iv_ctx, _mi_representations, _iv_selectionSet));
        } ;
    }
}
