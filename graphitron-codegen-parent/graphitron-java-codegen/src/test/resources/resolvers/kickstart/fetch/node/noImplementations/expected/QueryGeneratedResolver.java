package fake.code.generated.resolvers.query;

import fake.graphql.example.api.QueryResolver;
import fake.graphql.example.model.Node;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.IllegalArgumentException;
import java.lang.Override;
import java.lang.String;
import java.util.concurrent.CompletableFuture;
import no.sikt.graphql.helpers.resolvers.DataFetcherHelper;

public class QueryGeneratedResolver implements QueryResolver {
    @Override
    public CompletableFuture<Node> node(String id, DataFetchingEnvironment env) throws Exception {
        var _targetType = null;
        if (_targetType == null) {
            throw new IllegalArgumentException("Could not resolve input id with value " + id + " within type " + _targetType);
        }
        var _loaderName = _targetType + "_node";
        var _fetcher = new DataFetcherHelper(env);

        switch(_targetType) {
            default: throw new IllegalArgumentException("Could not resolve input id with value " + id + " within type " + _targetType);
        }
    }
}
