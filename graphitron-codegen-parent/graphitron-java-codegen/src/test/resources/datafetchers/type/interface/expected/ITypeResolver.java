package fake.code.generated.queries.query;

import fake.graphql.example.model.A;
import graphql.schema.TypeResolver;
import java.lang.IllegalArgumentException;
import java.lang.Object;
import java.lang.String;

public class ITypeResolver {
    public static TypeResolver iTypeResolver() {
        return _iv_env -> _iv_env.getSchema().getObjectType(getName(_iv_env.getObject()));
    }

    public static String getName(Object _iv_obj) {
        if (_iv_obj instanceof A) {
            return "A";
        }
        throw new IllegalArgumentException("Type of " + _iv_obj + " can not be resolved.");
    }
}
