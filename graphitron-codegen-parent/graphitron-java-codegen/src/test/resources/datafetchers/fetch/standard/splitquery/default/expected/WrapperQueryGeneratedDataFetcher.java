package fake.code.generated.resolvers.query;

import fake.code.generated.queries.WrapperQueryDBQueries;
import fake.graphql.example.model.DummyType;
import fake.graphql.example.model.Wrapper;
import graphql.schema.DataFetcher;
import java.util.concurrent.CompletableFuture;
import no.sikt.graphql.helpers.resolvers.DataFetcherHelper;

public class WrapperQueryGeneratedDataFetcher {
    public static DataFetcher<CompletableFuture<DummyType>> query() {
        return env -> {
            Wrapper wrapper = env.getSource();
            return new DataFetcherHelper(env).load(wrapper.getQueryKey(), (ctx, resolverKeys, selectionSet) -> WrapperQueryDBQueries.queryForWrapper(ctx, resolverKeys, selectionSet));
        };
    }
}
