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
import org.jooq.DSLContext;
public class QueryGeneratedResolver implements QueryResolver {
    @Inject
    DSLContext ctx;
    @Inject
    private NodeIdHandler nodeIdHandler;
    @Override
    public CompletableFuture<Node> node(String id, DataFetchingEnvironment env) throws Exception {
        String tableName = nodeIdHandler.getTable(id).getName();
        throw new IllegalArgumentException("Could not find dataloader for id with name " + tableName);
    }
}
