import fake.code.generated.queries.query.CustomerDBQueries;
import fake.graphql.example.api.QueryResolver;
import fake.graphql.example.model.Node;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.IllegalArgumentException;
import java.lang.Override;
import java.lang.String;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import no.fellesstudentsystem.graphql.NodeIdHandler;
import no.fellesstudentsystem.graphql.helpers.resolvers.DataFetcher;
import no.sikt.graphitron.jooq.generated.testdata.public_.tables.Customer;
import org.jooq.DSLContext;
public abstract class QueryGeneratedResolver implements QueryResolver {
    @Inject
    DSLContext ctx;
    @Inject
    private NodeIdHandler nodeIdHandler;
    @Override
    public CompletableFuture<Node> node(String id, DataFetchingEnvironment env) throws Exception {
        String tableName = nodeIdHandler.getTable(id).getName();
        if (Customer.CUSTOMER.getName().equals(tableName)) {
            return new DataFetcher(env, this.ctx).loadInterface(tableName, id, (ctx, ids, selectionSet) -> CustomerDBQueries.loadCustomerByIdsAsNode(ctx, ids, selectionSet));
        }
        throw new IllegalArgumentException("Could not find dataloader for id with name " + tableName);
    }
}
