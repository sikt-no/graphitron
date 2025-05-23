import graphql.schema.idl.TypeDefinitionRegistry;
import java.util.Set;
import no.sikt.graphql.schema.SchemaReadingHelper;

public class TypeRegistry {
    public static TypeDefinitionRegistry getTypeRegistry() {
        return SchemaReadingHelper.getTypeDefinitionRegistry(
                Set.of(
                        "src/test/resources/components/basic/Customer.graphqls",
                        "src/test/resources/components/basic/CustomerQuery.graphqls",
                        "src/test/resources/typeregistry/default/schema.graphqls",
                        "../../graphitron-common/src/main/resources/directives.graphqls"
                )
        );
    }
}
