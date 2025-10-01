import fake.code.generated.resolvers.operations.QueryQueryGeneratedDataFetcher;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.TypeRuntimeWiring;

public class Wiring {
    public static RuntimeWiring.Builder getRuntimeWiringBuilder() {
        var wiring = RuntimeWiring.newRuntimeWiring();
        wiring.type(TypeRuntimeWiring.newTypeWiring("Query").dataFetcher("query", QueryQueryGeneratedDataFetcher.query()));
        return wiring;
    }

    public static RuntimeWiring getRuntimeWiring() {
        return getRuntimeWiringBuilder().build();
    }
}
