import fake.code.generated.datafetchers.query.QueryEntityGeneratedDataFetcher;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.TypeRuntimeWiring;

public class Wiring {
    public static RuntimeWiring getRuntimeWiring() {
        var wiring = RuntimeWiring.newRuntimeWiring();
        wiring.type(TypeRuntimeWiring.newTypeWiring("Query").dataFetcher("_entities", QueryEntityGeneratedDataFetcher.entityFetcher()));
        wiring.type(TypeRuntimeWiring.newTypeWiring("_Entity").typeResolver(QueryEntityGeneratedDataFetcher.entityTypeResolver()));
        return wiring.build();
    }
}
