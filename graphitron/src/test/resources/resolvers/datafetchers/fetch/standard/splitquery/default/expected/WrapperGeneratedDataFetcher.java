package fake.code.generated.resolvers.query;

import fake.code.generated.queries.WrapperDBQueries;
import fake.graphql.example.model.DummyType;
import fake.graphql.example.model.Wrapper;
import graphql.schema.DataFetcher;
import java.util.concurrent.CompletableFuture;
import no.sikt.graphql.helpers.resolvers.DataFetcherHelper;

public class WrapperGeneratedDataFetcher {
    public static DataFetcher<CompletableFuture<DummyType>> query() {
        return env -> {
            var wrapper = ((Wrapper) env.getSource());
            return new DataFetcherHelper(env).load(wrapper.getId(), (ctx, ids, selectionSet) -> WrapperDBQueries.queryForWrapper(ctx, ids, selectionSet));
        };
    }
}
