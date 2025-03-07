package fake.code.generated.queries.query;

import fake.code.generated.queries.query.CustomerDBQueries;
import fake.graphql.example.model.Customer;
import fake.graphql.example.model._Entity;
import graphql.schema.DataFetcher;
import java.lang.Object;
import java.lang.String;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import no.sikt.graphql.helpers.resolvers.ResolverHelpers;
import org.jooq.DSLContext;

public class QueryEntityGeneratedDataFetcher {
    public static DataFetcher<List<_Entity>> entityFetcher() {
        return env -> ((List<Map<String, Object>>) env.getArgument("representations")).stream().map(internal_it_ -> {
            var ctx = (DSLContext) env.getLocalContext();
            switch ((String) internal_it_.get("__typename")) {
                case "Customer": return ResolverHelpers.transformDTO(CustomerDBQueries.customerAsEntity(ctx, internal_it_), Customer.class);
                default: return null;
            }
        } ).collect(Collectors.toList());
    }
}
