package fake.code.generated.resolvers.query;

import fake.code.generated.queries.query.QueryDBQueries;
import fake.graphql.example.api.QueryResolver;
import fake.graphql.example.model.Titled;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import no.sikt.graphql.helpers.resolvers.DataFetcher;

public class QueryGeneratedResolver implements QueryResolver {
    @Override
    public CompletableFuture<List<Titled>> titled(DataFetchingEnvironment env) throws Exception {
        return new DataFetcher(env).load(
                (ctx, selectionSet) -> QueryDBQueries.titledForQuery(ctx, selectionSet));
    }
}
