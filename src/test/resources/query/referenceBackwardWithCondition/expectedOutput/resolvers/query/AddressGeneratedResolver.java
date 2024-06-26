package fake.code.generated.resolvers.query;

import fake.code.generated.queries.query.AddressDBQueries;
import fake.graphql.example.api.AddressResolver;
import fake.graphql.example.model.Address;
import fake.graphql.example.model.Store;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.lang.String;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import no.fellesstudentsystem.graphql.helpers.resolvers.DataFetcher;
import org.jooq.DSLContext;

public class AddressGeneratedResolver implements AddressResolver {
    @Inject
    DSLContext ctx;

    @Override
    public CompletableFuture<Store> store(Address address, String id, DataFetchingEnvironment env)
            throws Exception {
        return new DataFetcher(env, this.ctx).load("storeForAddress", address.getId(), (ctx, ids, selectionSet) -> AddressDBQueries.storeForAddress(ctx, ids, id, selectionSet));
    }
}
