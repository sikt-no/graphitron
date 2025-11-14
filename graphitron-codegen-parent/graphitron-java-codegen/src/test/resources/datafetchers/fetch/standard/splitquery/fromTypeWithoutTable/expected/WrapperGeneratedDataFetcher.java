package fake.code.generated.resolvers.query;

import fake.code.generated.queries.WrapperDBQueries;
import fake.graphql.example.model.CustomerTable;
import fake.graphql.example.model.Wrapper;
import graphql.schema.DataFetcher;
import java.util.concurrent.CompletableFuture;
import no.sikt.graphql.helpers.resolvers.DataFetcherHelper;

public class WrapperGeneratedDataFetcher {
    public static DataFetcher<CompletableFuture<CustomerTable>> customer() {
        return _iv_env -> {
            Wrapper _os_wrapper = _iv_env.getSource();
            return new DataFetcherHelper(_iv_env).load(_os_wrapper.getCustomerKey(), (_iv_ctx, _iv_keys, _iv_selectionSet) -> WrapperDBQueries.customerForWrapper(_iv_ctx, _iv_keys, _iv_selectionSet));
        };
    }
}
