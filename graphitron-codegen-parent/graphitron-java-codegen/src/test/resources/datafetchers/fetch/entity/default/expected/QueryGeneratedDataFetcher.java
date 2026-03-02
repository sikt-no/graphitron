package fake.code.generated.queries.query;

import fake.code.generated.queries.CustomerDBQueries;
import fake.graphql.example.model._Entity;
import graphql.schema.DataFetcher;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import no.sikt.graphql.helpers.resolvers.DataFetcherHelper;

public class QueryGeneratedDataFetcher {
    public static DataFetcher<CompletableFuture<List<_Entity>>> _entities() {
        return _iv_env -> {
            List<?> _mi_representations = _iv_env.getArgument("representations");
            return new DataFetcherHelper(_iv_env).loadLookupEntities(
                    _mi_representations,
                    Map.of(
                            "Customer", (_iv_ctx, _iv_reps, _iv_selectionSet) -> CustomerDBQueries.customerFor_Entity(_iv_ctx, _iv_reps, _iv_selectionSet)
                    )
            );
        } ;
    }
}
