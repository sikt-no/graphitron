import fake.code.generated.queries.query.QueryDBQueries;
import fake.graphql.example.model.Customer;
import graphql.schema.DataFetcher;
import java.util.concurrent.CompletableFuture;
import no.sikt.graphql.helpers.resolvers.DataFetcherHelper;

public class QueryGeneratedDataFetcher {
    public static DataFetcher<CompletableFuture<Customer>> query() {
        return env -> {
            return new DataFetcherHelper(env).load((ctx, selectionSet) -> QueryDBQueries.queryForQuery(ctx, selectionSet));
        } ;
    }
}
