package fake.code.generated.queries.query;

import graphql.schema.TypeResolver;
import java.lang.Object;
import java.lang.String;
import java.util.Map;

public class EntityTypeResolver {
    public static TypeResolver entityTypeResolver() {
        return env -> {
            var _obj = env.getObject();
            if (!(_obj instanceof Map)) {
                return null;
            }
            return env.getSchema().getObjectType((String) ((Map<String, Object>) _obj).get("__typename"));
        };
    }
}
