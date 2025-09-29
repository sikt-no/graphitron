package fake.code.generated.queries.query;

import fake.graphql.example.model.Customer;
import graphql.schema.TypeResolver;
import java.lang.IllegalArgumentException;
import java.lang.Object;
import java.lang.String;

public class EntityTypeResolver {
    public static TypeResolver entityTypeResolver() {
        return env -> env.getSchema().getObjectType(getName(env.getObject()));
    }

    public static String getName(Object _obj) {
        if (_obj instanceof Customer) {
            return "Customer";
        }
        throw new IllegalArgumentException("Type of " + _obj + " can not be resolved.");
    }
}
