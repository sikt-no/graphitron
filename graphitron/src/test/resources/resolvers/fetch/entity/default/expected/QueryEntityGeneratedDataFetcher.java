package fake.code.generated.queries.query;

import fake.code.generated.queries.query.CustomerDBQueries;
import java.lang.Object;
import java.lang.String;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import graphql.schema.DataFetcher;

public class QueryEntityGeneratedDataFetcher {
    public DataFetcher<List<Map<String, Object>>> entityFetcher() {
        return env -> ((List<Map<String, Object>>) env.getArgument("representations")).stream().map(internal_it_ -> {
            var ctx = env.getLocalContext();
            var _typeName = (String) internal_it_.get("__typename");
            var _obj = new HashMap<String, Object>();
            _obj.put("__typename", _typeName);
            switch (_typeName) {
                case "Customer":
                    _obj.putAll(CustomerDBQueries.customerAsEntity(ctx, internal_it_));break;
                default: return null;
            }
            return _obj;
        } ).collect(Collectors.toList());
    }
}
