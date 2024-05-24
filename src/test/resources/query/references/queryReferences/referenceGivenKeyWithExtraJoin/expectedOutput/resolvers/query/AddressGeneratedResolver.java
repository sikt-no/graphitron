package fake.code.generated.resolvers.query;

import fake.code.generated.queries.query.AddressDBQueries;
import fake.graphql.example.api.AddressResolver;
import fake.graphql.example.model.Address;
import fake.graphql.example.model.Store;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import no.fellesstudentsystem.graphql.helpers.resolvers.DataFetcher;
import org.jooq.DSLContext;

public class AddressGeneratedResolver implements AddressResolver {
    @Inject
    DSLContext ctx;

    @Inject
    private AddressDBQueries addressDBQueries;

    @Override
    public CompletableFuture<List<Store>> stores0(Address address, DataFetchingEnvironment env)
            throws Exception {
        return new DataFetcher(env, this.ctx).load("stores0ForAddress", address.getId(), (ctx, ids, selectionSet) -> addressDBQueries.stores0ForAddress(ctx, ids, selectionSet));
    }

    @Override
    public CompletableFuture<List<Store>> stores1(Address address, DataFetchingEnvironment env)
            throws Exception {
        return new DataFetcher(env, this.ctx).loadNonNullable("stores1ForAddress", address.getId(), (ctx, ids, selectionSet) -> addressDBQueries.stores1ForAddress(ctx, ids, selectionSet));
    }
}
