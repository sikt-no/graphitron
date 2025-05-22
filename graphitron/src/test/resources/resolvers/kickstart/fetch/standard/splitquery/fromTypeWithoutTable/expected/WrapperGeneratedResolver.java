package fake.code.generated.resolvers.query;

import fake.code.generated.queries.WrapperDBQueries;
import fake.graphql.example.api.WrapperResolver;
import fake.graphql.example.model.CustomerTable;
import fake.graphql.example.model.Wrapper;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.util.concurrent.CompletableFuture;
import no.sikt.graphql.helpers.resolvers.DataFetcherHelper;

public class WrapperGeneratedResolver implements WrapperResolver {
    @Override
    public CompletableFuture<CustomerTable> customer(Wrapper wrapper, DataFetchingEnvironment env)
            throws Exception {
        return new DataFetcherHelper(env).load(wrapper.getId(), (ctx, ids, selectionSet) -> WrapperDBQueries.customerForWrapper(ctx, ids, selectionSet));
    }
}
