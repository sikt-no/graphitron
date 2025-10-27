package fake.code.generated.queries.query;

import graphql.schema.TypeResolver;
import java.lang.Object;
import java.lang.String;
import java.util.Map;

public class QueryEntityGeneratedDataFetcher {
    public static TypeResolver entityTypeResolver() {
        return _iv_env -> {
            var _iv_obj = _iv_env.getObject();
            if (!(_iv_obj instanceof Map)) {
                return null;
            }
            return _iv_env.getSchema().getObjectType((String) ((Map<String, Object>) _iv_obj).get("__typename"));
        };
    }
}
