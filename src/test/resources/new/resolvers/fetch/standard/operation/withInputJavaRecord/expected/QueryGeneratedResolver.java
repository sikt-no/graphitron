package fake.code.generated.resolvers.query;

import fake.code.generated.queries.query.QueryDBQueries;
import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.api.QueryResolver;
import fake.graphql.example.model.DummyInputRecord;
import fake.graphql.example.model.DummyType;
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
    public CompletableFuture<DummyType> query(DummyInputRecord in, DataFetchingEnvironment env)
            throws Exception {
        var transform = new RecordTransformer(env, this.ctx);

        var inRecord = transform.dummyInputRecordToJavaRecord(in, "in");

        return new DataFetcher(env, this.ctx).load((ctx, selectionSet) -> QueryDBQueries.queryForQuery(ctx, inRecord, selectionSet));
    }

    @Override
    public CompletableFuture<List<DummyType>> queryListed(List<DummyInputRecord> in,
                                                          DataFetchingEnvironment env) throws Exception {
        var transform = new RecordTransformer(env, this.ctx);

        var inRecordList = transform.dummyInputRecordToJavaRecord(in, "in");

        return new DataFetcher(env, this.ctx).load((ctx, selectionSet) -> QueryDBQueries.queryListedForQuery(ctx, inRecordList, selectionSet));
    }
}
