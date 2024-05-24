package fake.code.generated.resolvers.query;

import fake.code.generated.queries.query.StoreCustomerCityDBQueries;
import fake.graphql.example.api.StoreCustomerCityResolver;
import fake.graphql.example.model.City;
import fake.graphql.example.model.StoreCustomerCity;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import no.fellesstudentsystem.graphql.helpers.resolvers.DataFetcher;
import org.jooq.DSLContext;

public class StoreCustomerCityGeneratedResolver implements StoreCustomerCityResolver {
    @Inject
    DSLContext ctx;

    @Inject
    private StoreCustomerCityDBQueries storeCustomerCityDBQueries;

    @Override
    public CompletableFuture<City> city(StoreCustomerCity storeCustomerCity,
                                        DataFetchingEnvironment env) throws Exception {
        return new DataFetcher(env, this.ctx).load("cityForStoreCustomerCity", storeCustomerCity.getId(), (ctx, ids, selectionSet) -> storeCustomerCityDBQueries.cityForStoreCustomerCity(ctx, ids, selectionSet));
    }
}
