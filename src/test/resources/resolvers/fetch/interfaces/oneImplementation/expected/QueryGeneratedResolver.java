package fake.code.generated.resolvers.query;

import fake.code.generated.queries.query.CustomerDBQueries;
import fake.graphql.example.api.QueryResolver;
import fake.graphql.example.model.Node;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.IllegalArgumentException;
import java.lang.Override;
import java.lang.String;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import no.fellesstudentsystem.graphql.NodeIdHandler;
import no.fellesstudentsystem.graphql.helpers.resolvers.DataFetcher;
import no.sikt.graphitron.jooq.generated.testdata.public_.tables.Customer;

public abstract class QueryGeneratedResolver implements QueryResolver {
    private static final Map<String, String> TABLE_TO_TYPE = Map.ofEntries(Map.entry(Customer.CUSTOMER.getName(), "Customer"));

    @Inject
    private NodeIdHandler nodeIdHandler;

    @Override
    public CompletableFuture<Node> node(String id, DataFetchingEnvironment env) throws Exception {
        var _targetType = TABLE_TO_TYPE.get(nodeIdHandler.getTable(id).getName());
        if (_targetType == null) {
            throw new IllegalArgumentException("Could not resolve input id with value " + id + " within type " + _targetType);
        }
        var _loaderName = _targetType + "_node";
        var _fetcher = new DataFetcher(env);

        switch(_targetType) {
            case "Customer": return _fetcher.loadInterface(_loaderName, id, (ctx, ids, selectionSet) -> CustomerDBQueries.loadCustomerByIdsAsNode(ctx, ids, selectionSet));
            default: throw new IllegalArgumentException("Could not resolve input id with value " + id + " within type " + _targetType);
        }
    }
}
