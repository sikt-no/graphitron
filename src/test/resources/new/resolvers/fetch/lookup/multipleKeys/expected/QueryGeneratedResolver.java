package fake.code.generated.resolvers.query;

import fake.code.generated.queries.query.QueryDBQueries;
import fake.graphql.example.api.QueryResolver;
import fake.graphql.example.model.DummyType;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.lang.String;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import no.fellesstudentsystem.graphql.helpers.resolvers.DataFetcher;
import org.jooq.DSLContext;

public class QueryGeneratedResolver implements QueryResolver {
    @Inject
    DSLContext ctx;

    @Override
    public CompletableFuture<DummyType> query(List<String> id0, List<String> id1, List<String> id2,
                                              DataFetchingEnvironment env) throws Exception {
        var keys = List.of(id0, id1, id2);
        return new DataFetcher(env, this.ctx).loadLookup(keys, (ctx, ids, selectionSet) -> QueryDBQueries.queryForQuery(ctx, id0, id1, id2, selectionSet));
    }
}
