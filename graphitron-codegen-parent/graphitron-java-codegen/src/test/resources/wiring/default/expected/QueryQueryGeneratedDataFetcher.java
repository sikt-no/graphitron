import fake.code.generated.queries.QueryQueryDBQueries;
import fake.graphql.example.model.Customer;
import graphql.schema.DataFetcher;
import java.util.concurrent.CompletableFuture;
import no.sikt.graphql.helpers.resolvers.DataFetcherHelper;

public class QueryQueryGeneratedDataFetcher {
    public static DataFetcher<CompletableFuture<Customer>> query() {
        return env -> {
            return new DataFetcherHelper(env).load((ctx, selectionSet) -> QueryQueryDBQueries.queryForQuery(ctx, selectionSet));
        } ;
    }
}
