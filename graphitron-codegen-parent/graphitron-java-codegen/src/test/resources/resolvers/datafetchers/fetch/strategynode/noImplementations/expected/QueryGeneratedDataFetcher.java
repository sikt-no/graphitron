
package fake.code.generated.resolvers.query;

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
            String id = env.getArgument("id");
            var _typeId = null;
            if (_typeId == null) {
                throw new IllegalArgumentException("Could not resolve input id with value " + id + " within type " + _typeId);
            }
            var _loaderName = _typeId + "_node";
            var _fetcher = new DataFetcherHelper(env);

            switch (_typeId) {
                default: throw new IllegalArgumentException("Could not resolve input id with value " + id + " within type " + _typeId);
            }
        };
    }
}
