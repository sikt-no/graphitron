package fake.code.generated.resolvers.query;

import fake.code.generated.queries.query.StoreCustomerCityDBQueries;
import fake.graphql.example.package.api.StoreCustomerCityResolver;
import fake.graphql.example.package.model.City;
import fake.graphql.example.package.model.StoreCustomerCity;
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

public class StoreCustomerCityGeneratedResolver implements StoreCustomerCityResolver {
    @Inject
    DSLContext ctx;

    @Inject
    private StoreCustomerCityDBQueries storeCustomerCityDBQueries;

    @Override
    public CompletableFuture<City> city(StoreCustomerCity storeCustomerCity,
                                        DataFetchingEnvironment env) throws Exception {
        var ctx = ResolverHelpers.selectContext(env, this.ctx);
        DataLoader<String, City> loader = DataLoaders.getDataLoader(env, "cityForStoreCustomerCity", (ids, selectionSet) -> storeCustomerCityDBQueries.cityForStoreCustomerCity(ctx, ids, selectionSet));
        return DataLoaders.load(loader, storeCustomerCity.getId(), env);
    }
}
