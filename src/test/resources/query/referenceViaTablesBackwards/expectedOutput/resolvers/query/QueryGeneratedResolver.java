package fake.code.example.package.resolvers.query;

import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.lang.String;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import fake.graphql.example.package.api.QueryResolver;
import fake.graphql.example.package.model.StudentVedInstitusjon;
import no.fellesstudentsystem.graphql.helpers.EnvironmentUtils;
import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;
import fake.code.example.package.queries.query.QueryDBQueries;
import org.jooq.DSLContext;

public class QueryGeneratedResolver implements QueryResolver {
    @Inject
    DSLContext ctx;

    @Inject
    private QueryDBQueries queryDBQueries;

    @Override
    public CompletableFuture<List<StudentVedInstitusjon>> student(String id,
            DataFetchingEnvironment env) throws Exception {
        var ctx = env.getLocalContext() == null ? this.ctx : (DSLContext) env.getLocalContext();
        var dbResult = queryDBQueries.studentForQuery(ctx, id, new SelectionSet(EnvironmentUtils.getSelectionSetsFromEnvironment(env)));
        return CompletableFuture.completedFuture(dbResult);
    }
}
