package fake.code.generated.resolvers.query;

import fake.code.generated.queries.WrapperCustomerDBQueries;
import fake.graphql.example.model.CustomerTable;
import fake.graphql.example.model.Wrapper;
import graphql.schema.DataFetcher;
import java.util.concurrent.CompletableFuture;
import no.sikt.graphql.helpers.resolvers.DataFetcherHelper;

public class WrapperCustomerGeneratedDataFetcher {
    public static DataFetcher<CompletableFuture<CustomerTable>> customer() {
        return env -> {
            Wrapper wrapper = env.getSource();
            return new DataFetcherHelper(env).load(wrapper.getCustomerKey(), (ctx, resolverKeys, selectionSet) -> WrapperCustomerDBQueries.customerForWrapper(ctx, resolverKeys, selectionSet));
        };
    }
}
