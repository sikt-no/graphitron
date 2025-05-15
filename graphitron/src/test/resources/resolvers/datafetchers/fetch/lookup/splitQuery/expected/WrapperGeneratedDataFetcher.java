package fake.code.generated.resolvers.query;

import fake.code.generated.queries.query.WrapperDBQueries;
import fake.graphql.example.model.DummyType;
import fake.graphql.example.model.Wrapper;
import graphql.schema.DataFetcher;
import java.lang.String;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import no.sikt.graphql.helpers.resolvers.DataFetcherHelper;

public class WrapperGeneratedDataFetcher {
    public static DataFetcher<CompletableFuture<DummyType>> query() {
        return env -> {
            var _args = env.getArguments();
            var wrapper = ((Wrapper) env.getSource());
            var id = ((List<String>) _args.get("id"));
            var keys = List.of(id);
            return new DataFetcherHelper(env).loadLookup(keys, (ctx, ids, selectionSet) -> WrapperDBQueries.queryForWrapper(ctx, id, selectionSet));
        };
    }
}

