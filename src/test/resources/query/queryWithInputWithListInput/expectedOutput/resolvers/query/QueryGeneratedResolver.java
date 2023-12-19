package fake.code.generated.resolvers.query;

import fake.code.generated.queries.query.QueryDBQueries;
import fake.graphql.example.package.api.QueryResolver;
import fake.graphql.example.package.model.Customer;
import fake.graphql.example.package.model.CustomerFilter;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.lang.String;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import no.fellesstudentsystem.graphql.helpers.EnvironmentUtils;
import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;

public class QueryGeneratedResolver implements QueryResolver {
    @Inject
    DSLContext ctx;

    @Inject
    private QueryDBQueries queryDBQueries;

    @Override
    public CompletableFuture<List<Customer>> customer(CustomerFilter filter, String storeId,
                                                      DataFetchingEnvironment env) throws Exception {
        var ctx = env.getLocalContext() == null ? this.ctx : (DSLContext) env.getLocalContext();
        var selectionSet = new SelectionSet(EnvironmentUtils.getSelectionSetsFromEnvironment(env));
        var dbResult = queryDBQueries.customerForQuery(ctx, filter, storeId, selectionSet);
        return CompletableFuture.completedFuture(dbResult);
    }
}
