import fake.code.generated.resolvers.query.QueryGeneratedDataFetcher;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.TypeRuntimeWiring;

public class Wiring {
    public static RuntimeWiring getRuntimeWiring() {
        var wiring = RuntimeWiring.newRuntimeWiring();
        wiring.type(TypeRuntimeWiring.newTypeWiring("Query").dataFetcher("query", QueryGeneratedDataFetcher.query()));
        return wiring.build();
    }
}
