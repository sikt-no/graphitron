package fake.code.generated.resolvers.query;

import fake.code.generated.queries.query.StoreCustomerDBQueries;
import fake.graphql.example.package.api.StoreCustomerResolver;
import fake.graphql.example.package.model.Customer;
import fake.graphql.example.package.model.StoreCustomer;
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

public class StoreCustomerGeneratedResolver implements StoreCustomerResolver {
    @Inject
    DSLContext ctx;

    @Inject
    private StoreCustomerDBQueries storeCustomerDBQueries;

    @Override
    public CompletableFuture<Customer> customer(StoreCustomer storeCustomer,
                                                DataFetchingEnvironment env) throws Exception {
        var ctx = env.getLocalContext() == null ? this.ctx : (DSLContext) env.getLocalContext();
        DataLoader<String, Customer> loader = env.getDataLoaderRegistry().computeIfAbsent("customerForStoreCustomer", name -> {
            var batchLoader = (MappedBatchLoaderWithContext<String, Customer>) (keys, batchEnvLoader) -> {
                var keyToId = keys.stream().collect(
                        Collectors.toMap(s -> s, s -> s.substring(s.lastIndexOf("||") + 2)));
                var idSet = new HashSet<>(keyToId.values());
                var selectionSet = new SelectionSet(EnvironmentUtils.getSelectionSetsFromEnvironment(batchEnvLoader));
                var dbResult = storeCustomerDBQueries.customerForStoreCustomer(ctx, idSet, selectionSet);
                var mapResult = keyToId.entrySet().stream()
                        .filter(it -> dbResult.get(it.getValue()) != null)
                        .collect(Collectors.toMap(Map.Entry::getKey, it -> dbResult.get(it.getValue())));
                return CompletableFuture.completedFuture(mapResult);
            } ;
            return DataLoaderFactory.newMappedDataLoader(batchLoader);
        } );
        return loader.load(env.getExecutionStepInfo().getPath().toString() + "||" + storeCustomer.getId(), env);
    }
}
