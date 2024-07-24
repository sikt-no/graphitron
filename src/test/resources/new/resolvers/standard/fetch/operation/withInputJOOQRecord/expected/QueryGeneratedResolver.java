package fake.code.generated.resolvers.query;

import fake.code.generated.queries.query.QueryDBQueries;
import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.api.QueryResolver;
import fake.graphql.example.model.Customer;
import fake.graphql.example.model.Target;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import no.fellesstudentsystem.graphql.helpers.resolvers.DataFetcher;
import org.jooq.DSLContext;

public class QueryGeneratedResolver implements QueryResolver {
    @Inject
    DSLContext ctx;

    @Override
    public CompletableFuture<Target> query(Customer in, DataFetchingEnvironment env) throws
            Exception {
        var transform = new RecordTransformer(env, this.ctx);

        var inRecord = transform.customerToJOOQRecord(in, "in");

        return new DataFetcher(env, this.ctx).load((ctx, selectionSet) -> QueryDBQueries.queryForQuery(ctx, inRecord, selectionSet));
    }

    @Override
    public CompletableFuture<List<Target>> queryListed(List<Customer> in,
                                                       DataFetchingEnvironment env) throws Exception {
        var transform = new RecordTransformer(env, this.ctx);

        var inRecordList = transform.customerToJOOQRecord(in, "in");

        return new DataFetcher(env, this.ctx).load((ctx, selectionSet) -> QueryDBQueries.queryListedForQuery(ctx, inRecordList, selectionSet));
    }
}
