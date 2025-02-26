import fake.code.generated.typeregistry.TypeRegistry;
import fake.code.generated.wiring.Wiring;
import graphql.schema.GraphQLSchema;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.TypeDefinitionRegistry;

public class Graphitron {
    public static TypeDefinitionRegistry getTypeRegistry() {
        return TypeRegistry.getTypeRegistry();
    }

    public static RuntimeWiring.Builder getRuntimeWiringBuilder() {
        return Wiring.getRuntimeWiringBuilder();
    }

    public static RuntimeWiring getRuntimeWiring() {
        return getRuntimeWiringBuilder().build();
    }

    public static GraphQLSchema getSchema() {
        var wiring = getRuntimeWiringBuilder();
        var registry = getTypeRegistry();
        return new SchemaGenerator().makeExecutableSchema(registry, wiring.build());
    }
}
