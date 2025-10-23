package fake.code.generated.queries.query;

import fake.code.generated.queries.CustomerDBQueries;
import fake.graphql.example.model.Customer;
import fake.graphql.example.model._Entity;
import graphql.schema.DataFetcher;
import java.lang.Object;
import java.lang.String;
import java.util.List;
import java.util.Map;

import no.sikt.graphql.helpers.resolvers.EnvironmentHandler;
import no.sikt.graphql.helpers.resolvers.ResolverHelpers;

public class QueryEntityGeneratedDataFetcher {
    public static DataFetcher<List<_Entity>> entityFetcher() {
        return _iv_env -> ((List<Map<String, Object>>) _iv_env.getArgument("representations")).stream().map(_iv_it -> {
            var _iv_ctx = new EnvironmentHandler(_iv_env).getCtx();
            switch ((String) _iv_it.get("__typename")) {
                case "Customer": return (_Entity) ResolverHelpers.transformDTO(CustomerDBQueries.customerAsEntity(_iv_ctx, _iv_it), Customer.class);
                default: return null;
            }
        } ).toList();
    }
}
