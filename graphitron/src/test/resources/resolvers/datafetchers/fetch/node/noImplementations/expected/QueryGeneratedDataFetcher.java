package fake.code.generated.resolvers.query;

import fake.graphql.example.model.Node;
import graphql.schema.DataFetcher;
import java.lang.IllegalArgumentException;
import java.lang.String;
import java.util.concurrent.CompletableFuture;
import no.sikt.graphql.NodeIdHandler;
import no.sikt.graphql.helpers.resolvers.DataFetcherHelper;

public class QueryGeneratedDataFetcher {
    public static DataFetcher<CompletableFuture<Node>> node(NodeIdHandler nodeIdHandler) {
        return env -> {
            var _args = env.getArguments();
            var id = ((String) _args.get("id"));
            var _targetType = null;
            if (_targetType == null) {
                throw new IllegalArgumentException("Could not resolve input id with value " + id + " within type " + _targetType);
            }
            var _loaderName = _targetType + "_node";
            var _fetcher = new DataFetcherHelper(env);

            switch (_targetType) {
                default: throw new IllegalArgumentException("Could not resolve input id with value " + id + " within type " + _targetType);
            }
        };
    }
}
