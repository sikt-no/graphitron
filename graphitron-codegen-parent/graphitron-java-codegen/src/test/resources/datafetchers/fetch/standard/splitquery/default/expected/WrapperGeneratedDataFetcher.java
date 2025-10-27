package fake.code.generated.resolvers.query;

import fake.code.generated.queries.WrapperDBQueries;
import fake.graphql.example.model.DummyType;
import fake.graphql.example.model.Wrapper;
import graphql.schema.DataFetcher;
import java.util.concurrent.CompletableFuture;
import no.sikt.graphql.helpers.resolvers.DataFetcherHelper;

public class WrapperGeneratedDataFetcher {
    public static DataFetcher<CompletableFuture<DummyType>> query() {
        return _iv_env -> {
            Wrapper wrapper = _iv_env.getSource();
            return new DataFetcherHelper(_iv_env).load(wrapper.getQueryKey(), (_iv_ctx, _iv_keys, _iv_selectionSet) -> WrapperDBQueries.queryForWrapper(_iv_ctx, _iv_keys, _iv_selectionSet));
        };
    }
}
