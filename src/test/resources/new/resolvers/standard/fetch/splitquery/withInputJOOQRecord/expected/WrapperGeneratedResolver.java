package fake.code.generated.resolvers.query;

import fake.code.generated.queries.query.WrapperDBQueries;
import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.api.WrapperResolver;
import fake.graphql.example.model.Customer;
import fake.graphql.example.model.Target;
import fake.graphql.example.model.Wrapper;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import no.fellesstudentsystem.graphql.helpers.resolvers.DataFetcher;
import org.jooq.DSLContext;

public class WrapperGeneratedResolver implements WrapperResolver {
    @Inject
    DSLContext ctx;

    @Override
    public CompletableFuture<Target> query(Wrapper wrapper, Customer in,
                                           DataFetchingEnvironment env) throws Exception {
        var transform = new RecordTransformer(env, this.ctx);

        var inRecord = transform.customerToJOOQRecord(in, "in");

        return new DataFetcher(env, this.ctx).load("queryForWrapper", wrapper.getId(), (ctx, ids, selectionSet) -> WrapperDBQueries.queryForWrapper(ctx, ids, inRecord, selectionSet));
    }
}
