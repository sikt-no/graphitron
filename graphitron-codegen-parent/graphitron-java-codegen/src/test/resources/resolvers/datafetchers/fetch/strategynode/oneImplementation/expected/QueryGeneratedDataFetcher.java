
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

    public static DataFetcher<CompletableFuture<Node>> node(NodeIdStrategy nodeIdStrategy) {
        return env -> {
            var _args = env.getArguments();
            var id = ((String) _args.get("id"));
            var _typeId = nodeIdStrategy.getTypeId(id);
            if (_typeId == null) {
                throw new IllegalArgumentException("Could not resolve input id with value " + id + " within type " + _typeId);
            }
            var _loaderName = _typeId + "_node";
            var _fetcher = new DataFetcherHelper(env);

            switch (_typeId) {
                case "CustomerType": return _fetcher.loadInterface(_loaderName, id, (ctx, ids, selectionSet) -> CustomerDBQueries.customerForNode(ctx, nodeIdStrategy, ids, selectionSet));
                default: throw new IllegalArgumentException("Could not resolve input id with value " + id + " within type " + _typeId);
            }
        };
    }
}
