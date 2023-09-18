package fake.code.generated.resolvers.query;

import fake.code.generated.queries.query.AddressDBQueries;
import fake.code.generated.queries.query.CustomerDBQueries;
import fake.graphql.example.package.api.CustomerResolver;
import fake.graphql.example.package.model.Address;
import fake.graphql.example.package.model.Customer;
import fake.graphql.example.package.model.Node;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.IllegalArgumentException;
import java.lang.Override;
import java.lang.String;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import no.fellesstudentsystem.graphql.helpers.EnvironmentUtils;
import no.fellesstudentsystem.graphql.helpers.FieldHelperHack;
import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;
import no.sikt.graphitron.jooq.generated.testdata.Tables;
import org.dataloader.DataLoaderFactory;
import org.dataloader.MappedBatchLoaderWithContext;
import org.jooq.DSLContext;

public class CustomerGeneratedResolver implements CustomerResolver {
    @Inject
    DSLContext ctx;

    @Inject
    private AddressDBQueries addressDBQueries;

    @Inject
    private CustomerDBQueries customerDBQueries;

    @Override
    public CompletableFuture<Node> nodeRef(Customer customer, String id,
            DataFetchingEnvironment env) throws Exception {
        var ctx = env.getLocalContext() == null ? this.ctx : (DSLContext) env.getLocalContext();
        String tablePartOfId = FieldHelperHack.getTablePartOf(id);

        if (tablePartOfId.equals(Tables.ADDRESS.getViewId().toString())) {
            return env.getDataLoaderRegistry().<String, Node>computeIfAbsent(tablePartOfId, name ->
                    DataLoaderFactory.newMappedDataLoader((MappedBatchLoaderWithContext<String, Address>) (keys, loaderEnvironment) ->
                        CompletableFuture.completedFuture(addressDBQueries.loadAddressByIdsAsNodeRef(ctx, keys, new SelectionSet(EnvironmentUtils.getSelectionSetsFromEnvironment(loaderEnvironment))))))
                    .load(id, env);
        }
        if (tablePartOfId.equals(Tables.CUSTOMER.getViewId().toString())) {
            return env.getDataLoaderRegistry().<String, Node>computeIfAbsent(tablePartOfId, name ->
                    DataLoaderFactory.newMappedDataLoader((MappedBatchLoaderWithContext<String, Customer>) (keys, loaderEnvironment) ->
                        CompletableFuture.completedFuture(customerDBQueries.loadCustomerByIdsAsNodeRef(ctx, keys, new SelectionSet(EnvironmentUtils.getSelectionSetsFromEnvironment(loaderEnvironment))))))
                    .load(id, env);
        }
        throw new IllegalArgumentException("could not find dataloader for id with prefix " + tablePartOfId);
    }
}