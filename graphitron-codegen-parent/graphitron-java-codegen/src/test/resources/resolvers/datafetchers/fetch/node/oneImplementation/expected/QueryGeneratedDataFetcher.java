package fake.code.generated.resolvers.query;

import fake.code.generated.queries.CustomerDBQueries;
import fake.graphql.example.model.Node;
import graphql.schema.DataFetcher;
import java.lang.IllegalArgumentException;
import java.lang.String;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import no.sikt.graphitron.jooq.generated.testdata.public_.tables.Customer;
import no.sikt.graphql.NodeIdHandler;
import no.sikt.graphql.helpers.resolvers.DataFetcherHelper;

public class QueryGeneratedDataFetcher {
    private static final Map<String, String> TABLE_TO_TYPE = Map.ofEntries(Map.entry(Customer.CUSTOMER.getName(), "Customer"));

    public static DataFetcher<CompletableFuture<Node>> node(NodeIdHandler nodeIdHandler) {
        return env -> {
            var _args = env.getArguments();
            String id = env.getArgument("id");
            var _targetType = TABLE_TO_TYPE.get(nodeIdHandler.getTable(id).getName());
            if (_targetType == null) {
                throw new IllegalArgumentException("Could not resolve input id with value " + id + " within type " + _targetType);
            }
            var _loaderName = _targetType + "_node";
            var _fetcher = new DataFetcherHelper(env);

            switch (_targetType) {
                case "Customer": return _fetcher.loadInterface(_loaderName, id, (ctx, ids, selectionSet) -> CustomerDBQueries.customerForNode(ctx, ids, selectionSet));
                default: throw new IllegalArgumentException("Could not resolve input id with value " + id + " within type " + _targetType);
            }
        };
    }
}
