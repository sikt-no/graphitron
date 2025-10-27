
package fake.code.generated.resolvers.query;

import fake.code.generated.queries.CustomerDBQueries;
import fake.graphql.example.model.Node;
import graphql.schema.DataFetcher;
import java.lang.IllegalArgumentException;
import java.lang.String;
import java.util.concurrent.CompletableFuture;
import no.sikt.graphql.NodeIdStrategy;
import no.sikt.graphql.helpers.resolvers.DataFetcherHelper;

public class QueryGeneratedDataFetcher {

    public static DataFetcher<CompletableFuture<Node>> node(NodeIdStrategy _iv_nodeIdStrategy) {
        return _iv_env -> {
            String id = _iv_env.getArgument("id");
            var _iv_typeId = _iv_nodeIdStrategy.getTypeId(id);
            if (_iv_typeId == null) {
                throw new IllegalArgumentException("Could not resolve input id with value " + id + " within type " + _iv_typeId);
            }
            var _iv_loaderName = _iv_typeId + "_node";
            var _iv_fetcher = new DataFetcherHelper(_iv_env);

            switch (_iv_typeId) {
                case "Customer": return _iv_fetcher.loadInterface(_iv_loaderName, id, (_iv_ctx, _iv_ids, _iv_selectionSet) -> CustomerDBQueries.customerForNode(_iv_ctx, _iv_nodeIdStrategy, _iv_ids, _iv_selectionSet));
                default: throw new IllegalArgumentException("Could not resolve input id with value " + id + " within type " + _iv_typeId);
            }
        };
    }
}
