import fake.code.generated.queries.QueryDBQueries;
import fake.graphql.example.model.Customer;
import graphql.schema.DataFetcher;
import java.util.concurrent.CompletableFuture;
import no.sikt.graphql.helpers.resolvers.DataFetcherHelper;

public class QueryGeneratedDataFetcher {
    public static DataFetcher<CompletableFuture<Customer>> query() {
        return _iv_env -> {
            return new DataFetcherHelper(_iv_env).load((_iv_ctx, _iv_selectionSet) -> QueryDBQueries.queryForQuery(_iv_ctx, _iv_selectionSet));
        } ;
    }
}
