package fake.code.example.package.resolvers.query;

import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.lang.String;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import fake.graphql.example.api.QueryResolver;
import fake.graphql.example.model.ProgramStudierett;
import no.fellesstudentsystem.graphql.helpers.resolvers.DataFetcher;
import fake.code.example.package.queries.query.QueryDBQueries;
import org.jooq.DSLContext;

public class QueryGeneratedResolver implements QueryResolver {
    @Inject
    DSLContext ctx;

    @Inject
    private QueryDBQueries queryDBQueries;

    @Override
    public CompletableFuture<List<ProgramStudierett>> programStudierett(String id,
            DataFetchingEnvironment env) throws Exception {
        return new DataFetcher(env, this.ctx).load((ctx, selectionSet) -> queryDBQueries.programStudierettForQuery(ctx, id, selectionSet));
    }
}
