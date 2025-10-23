package fake.code.generated.queries.query;

import fake.graphql.example.model.Customer;
import graphql.schema.TypeResolver;
import java.lang.IllegalArgumentException;
import java.lang.Object;
import java.lang.String;

public class EntityTypeResolver {
    public static TypeResolver entityTypeResolver() {
        return _iv_env -> _iv_env.getSchema().getObjectType(getName(_iv_env.getObject()));
    }

    public static String getName(Object _iv_obj) {
        if (_iv_obj instanceof Customer) {
            return "Customer";
        }
        throw new IllegalArgumentException("Type of " + _iv_obj + " can not be resolved.");
    }
}
