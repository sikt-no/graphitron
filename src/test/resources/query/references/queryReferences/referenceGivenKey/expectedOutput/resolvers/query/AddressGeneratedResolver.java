package fake.code.generated.resolvers.query;

import fake.code.generated.queries.query.AddressDBQueries;
import fake.graphql.example.package.api.AddressResolver;
import fake.graphql.example.package.model.Address;
import fake.graphql.example.package.model.Customer;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.lang.String;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import no.fellesstudentsystem.graphql.helpers.resolvers.DataLoaders;
import no.fellesstudentsystem.graphql.helpers.resolvers.ResolverHelpers;
import org.dataloader.DataLoader;
import org.jooq.DSLContext;

public class AddressGeneratedResolver implements AddressResolver {
    @Inject
    DSLContext ctx;

    @Inject
    private AddressDBQueries addressDBQueries;

    @Override
    public CompletableFuture<List<Customer>> customers(Address address, DataFetchingEnvironment env)
            throws Exception {
        var ctx = ResolverHelpers.selectContext(env, this.ctx);
        DataLoader<String, List<Customer>> loader = DataLoaders.getDataLoader(env, "customersForAddress", (ids, selectionSet) -> addressDBQueries.customersForAddress(ctx, ids, selectionSet));
        return DataLoaders.loadNonNullable(loader, address.getId(), env);
    }
}