package fake.code.generated.resolvers.query;

import fake.code.generated.queries.query.CustomerDBQueries;
import fake.graphql.example.package.api.CustomerResolver;
import fake.graphql.example.package.model.Address;
import fake.graphql.example.package.model.Customer;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.lang.String;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import javax.inject.Inject;
import no.fellesstudentsystem.graphql.helpers.EnvironmentUtils;
import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;
import org.dataloader.DataLoader;
import org.dataloader.DataLoaderFactory;
import org.dataloader.MappedBatchLoaderWithContext;
import org.jooq.DSLContext;

public class CustomerGeneratedResolver implements CustomerResolver {
    @Inject
    DSLContext ctx;

    @Inject
    private CustomerDBQueries customerDBQueries;

    @Override
    public CompletableFuture<List<Address>> historicalAddresses(Customer customer,
            DataFetchingEnvironment env) throws Exception {
        var ctx = env.getLocalContext() == null ? this.ctx : (DSLContext) env.getLocalContext();
        DataLoader<String, List<Address>> loader = env.getDataLoaderRegistry().computeIfAbsent("historicalAddressesForCustomer", name -> {
            var batchLoader = (MappedBatchLoaderWithContext<String, List<Address>>) (keys, batchEnvLoader) -> {
                var keyToId = keys.stream().collect(
                        Collectors.toMap(s -> s, s -> s.substring(s.lastIndexOf("||") + 2)));
                var idSet = new HashSet<>(keyToId.values());
                var selectionSet = new SelectionSet(EnvironmentUtils.getSelectionSetsFromEnvironment(batchEnvLoader));
                var dbResult = customerDBQueries.historicalAddressesForCustomer(ctx, idSet, selectionSet);
                var mapResult = keyToId.entrySet().stream()
                        .filter(it -> dbResult.get(it.getValue()) != null)
                        .collect(Collectors.toMap(Map.Entry::getKey, it -> dbResult.get(it.getValue())));
                return CompletableFuture.completedFuture(mapResult);
            } ;
            return DataLoaderFactory.newMappedDataLoader(batchLoader);
        } );
        return loader.load(env.getExecutionStepInfo().getPath().toString() + "||" + customer.getId(), env).thenApply(data -> Optional.ofNullable(data).orElse(List.of()));
    }
}