package fake.code.generated.queries.query;

import fake.graphql.example.model.A;
import graphql.schema.TypeResolver;
import java.lang.IllegalArgumentException;
import java.lang.Object;
import java.lang.String;

public class UTypeResolver {
    public static TypeResolver uTypeResolver() {
        return env -> env.getSchema().getObjectType(getName(env.getObject()));
    }

    public static String getName(Object _obj) {
        if (_obj instanceof A) {
            return "A";
        }
        throw new IllegalArgumentException("Type of " + _obj + " can not be resolved.");
    }
}
