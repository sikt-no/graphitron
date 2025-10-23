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
    private static final Map<String, String> _iv_tABLE_TO_TYPE = Map.ofEntries(Map.entry(Customer.CUSTOMER.getName(), "Customer"));

    public static DataFetcher<CompletableFuture<Node>> node(NodeIdHandler _iv_nodeIdHandler) {
        return _iv_env -> {
            String id = _iv_env.getArgument("id");
            var _iv_targetType = _iv_tABLE_TO_TYPE.get(_iv_nodeIdHandler.getTable(id).getName());
            if (_iv_targetType == null) {
                throw new IllegalArgumentException("Could not resolve input id with value " + id + " within type " + _iv_targetType);
            }
            var _iv_loaderName = _iv_targetType + "_node";
            var _iv_fetcher = new DataFetcherHelper(_iv_env);

            switch (_iv_targetType) {
                case "Customer": return _iv_fetcher.loadInterface(_iv_loaderName, id, (_iv_ctx, _iv_ids, _iv_selectionSet) -> CustomerDBQueries.customerForNode(_iv_ctx, _iv_ids, _iv_selectionSet));
                default: throw new IllegalArgumentException("Could not resolve input id with value " + id + " within type " + _iv_targetType);
            }
        };
    }
}
