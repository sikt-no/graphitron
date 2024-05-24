package fake.code.generated.resolvers.query;

import fake.code.generated.queries.query.StoreCustomerDBQueries;
import fake.graphql.example.api.StoreCustomerResolver;
import fake.graphql.example.model.Customer;
import fake.graphql.example.model.StoreCustomer;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;

import no.fellesstudentsystem.graphql.helpers.resolvers.DataFetcher;
import org.jooq.DSLContext;

public class StoreCustomerGeneratedResolver implements StoreCustomerResolver {
    @Inject
    DSLContext ctx;

    @Inject
    private StoreCustomerDBQueries storeCustomerDBQueries;

    @Override
    public CompletableFuture<Customer> customer(StoreCustomer storeCustomer,
                                                DataFetchingEnvironment env) throws Exception {
        return new DataFetcher(env, this.ctx).load("customerForStoreCustomer", storeCustomer.getId(), (ctx, ids, selectionSet) -> storeCustomerDBQueries.customerForStoreCustomer(ctx, ids, selectionSet));
    }
}
