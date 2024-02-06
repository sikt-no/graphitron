package fake.code.generated.resolvers.query;

import fake.code.generated.queries.query.StoreDBQueries;
import fake.graphql.example.api.StoreResolver;
import fake.graphql.example.model.City;
import fake.graphql.example.model.Store;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.lang.String;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import no.fellesstudentsystem.graphql.helpers.resolvers.DataLoaders;
import no.fellesstudentsystem.graphql.helpers.resolvers.ResolverHelpers;
import org.dataloader.DataLoader;
import org.jooq.DSLContext;

public class StoreGeneratedResolver implements StoreResolver {
    @Inject
    DSLContext ctx;

    @Inject
    private StoreDBQueries storeDBQueries;

    @Override
    public CompletableFuture<City> cityOfMostValuableCustomer(Store store,
            DataFetchingEnvironment env) throws Exception {
        var ctx = ResolverHelpers.selectContext(env, this.ctx);
        DataLoader<String, City> loader = DataLoaders.getDataLoader(env, "cityOfMostValuableCustomerForStore", (ids, selectionSet) -> storeDBQueries.cityOfMostValuableCustomerForStore(ctx, ids, selectionSet));
        return DataLoaders.load(loader, store.getId(), env);
    }
}
