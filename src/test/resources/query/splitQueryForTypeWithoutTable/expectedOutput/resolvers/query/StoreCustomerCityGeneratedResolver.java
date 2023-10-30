package fake.code.generated.resolvers.query;

import fake.code.generated.queries.query.StoreCustomerCityDBQueries;
import fake.graphql.example.package.api.StoreCustomerCityResolver;
import fake.graphql.example.package.model.City;
import fake.graphql.example.package.model.StoreCustomerCity;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.lang.String;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import javax.inject.Inject;
import no.fellesstudentsystem.graphql.helpers.EnvironmentUtils;
import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;
import org.dataloader.DataLoader;
import org.dataloader.DataLoaderFactory;
import org.dataloader.MappedBatchLoaderWithContext;
import org.jooq.DSLContext;

public class StoreCustomerCityGeneratedResolver implements StoreCustomerCityResolver {
    @Inject
    DSLContext ctx;

    @Inject
    private StoreCustomerCityDBQueries storeCustomerCityDBQueries;

    @Override
    public CompletableFuture<City> city(StoreCustomerCity storeCustomerCity,
                                        DataFetchingEnvironment env) throws Exception {
        var ctx = env.getLocalContext() == null ? this.ctx : (DSLContext) env.getLocalContext();
        DataLoader<String, City> loader = env.getDataLoaderRegistry().computeIfAbsent("cityForStoreCustomerCity", name -> {
            var batchLoader = (MappedBatchLoaderWithContext<String, City>) (keys, batchEnvLoader) -> {
                var keyToId = keys.stream().collect(
                        Collectors.toMap(s -> s, s -> s.substring(s.lastIndexOf("||") + 2)));
                var idSet = new HashSet<>(keyToId.values());
                var selectionSet = new SelectionSet(EnvironmentUtils.getSelectionSetsFromEnvironment(batchEnvLoader));
                var dbResult = storeCustomerCityDBQueries.cityForStoreCustomerCity(ctx, idSet, selectionSet);
                var mapResult = keyToId.entrySet().stream()
                        .filter(it -> dbResult.get(it.getValue()) != null)
                        .collect(Collectors.toMap(Map.Entry::getKey, it -> dbResult.get(it.getValue())));
                return CompletableFuture.completedFuture(mapResult);
            } ;
            return DataLoaderFactory.newMappedDataLoader(batchLoader);
        } );
        return loader.load(env.getExecutionStepInfo().getPath().toString() + "||" + storeCustomerCity.getId(), env);
    }
}
