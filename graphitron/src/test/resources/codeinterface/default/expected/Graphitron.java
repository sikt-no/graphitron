import fake.code.generated.typeregistry.TypeRegistry;
import fake.code.generated.wiring.Wiring;
import graphql.schema.idl.RuntimeWiring;
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
}
