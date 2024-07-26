package fake.code.generated.resolvers.query;

import fake.graphql.example.api.QueryResolver;
import fake.graphql.example.model.Node;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.IllegalArgumentException;
import java.lang.Override;
import java.lang.String;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import no.fellesstudentsystem.graphql.helpers.FieldHelperHack;
import org.jooq.DSLContext;

public class QueryGeneratedResolver implements QueryResolver {
    @Inject
    DSLContext ctx;

    @Override
    public CompletableFuture<Node> node(String id, DataFetchingEnvironment env) throws Exception {
        String tablePartOfId = FieldHelperHack.getTablePartOf(id);

        throw new IllegalArgumentException("Could not find dataloader for id with prefix " + tablePartOfId);
    }
}
