package no.fellesstudentsystem.graphitron_newtestorder.code;

import com.squareup.javapoet.CodeBlock;
import no.fellesstudentsystem.graphitron.generators.resolvers.fetch.FetchResolverMethodGenerator;
import no.fellesstudentsystem.graphitron_newtestorder.CodeBlockTest;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static no.fellesstudentsystem.graphitron_newtestorder.SchemaComponent.*;

@DisplayName("Resolver sorting - Resolvers with custom ordering")
public class ResolverSortingIDFunctionTest extends CodeBlockTest {
    @Override
    protected String getSubpath() {
        return "resolvers/fetch/sorting";
    }

    @Override
    protected CodeBlock makeCodeBlock(ProcessedSchema schema) {
        return new FetchResolverMethodGenerator(schema.getQueryType(), schema).getIDFunction(schema.getQueryType().getFieldByName("query"));
    }

    @Test
    @DisplayName("Basic resolver with a sorting parameter")
    public void defaultCase() {
        compareCodeBlockResult(
                "default",
                "(it) -> orderBy == null ? it.getId() :\n" +
                        "    java.util.Map.<java.lang.String, java.util.function.Function<fake.graphql.example.model.Customer, java.lang.String>>of(\n" +
                        "        \"NAME\", type -> type.getName()\n" +
                        "    ).get(orderBy.getOrderByField().toString()).apply(it)",
                CUSTOMER_CONNECTION_ORDER
        );
    }

    @Test
    @DisplayName("Resolver with two sorting parameters")
    void twoFields() {
        compareCodeBlockResult(
                "twoFields",
                "(it) -> orderBy == null ? it.getId() :\n" +
                        "    java.util.Map.<java.lang.String, java.util.function.Function<fake.graphql.example.model.Customer, java.lang.String>>of(\n" +
                        "        \"NAME\", type -> type.getName(),\n" +
                        "        \"STORE\", type -> type.getStoreId()\n" +
                        "    ).get(orderBy.getOrderByField().toString()).apply(it)",
                CUSTOMER_CONNECTION_ORDER
        );
    }

    @Test
    @DisplayName("Resolver with a nested sorting parameter")
    void nestedField() {
        compareCodeBlockResult(
                "nestedField",
                "(it) -> orderBy == null ? it.getId() :\n" +
                        "    java.util.Map.<java.lang.String, java.util.function.Function<fake.graphql.example.model.Customer, java.lang.String>>of(\n" +
                        "        \"NAME\", type -> type.getNested().getName()\n" +
                        "    ).get(orderBy.getOrderByField().toString()).apply(it)",
                CUSTOMER_CONNECTION_ORDER
        );
    }

    @Test
    @DisplayName("Resolver with a double nested sorting parameter")
    void doubleNestedField() {
        compareCodeBlockResult(
                "doubleNestedField",
                "(it) -> orderBy == null ? it.getId() :\n" +
                        "    java.util.Map.<java.lang.String, java.util.function.Function<fake.graphql.example.model.Customer, java.lang.String>>of(\n" +
                        "        \"NAME\", type -> type.getNested().getNested().getName()\n" +
                        "    ).get(orderBy.getOrderByField().toString()).apply(it)",
                CUSTOMER_CONNECTION_ORDER
        );
    }

    @Test
    @DisplayName("Resolver with a sorting parameter on a two field index")
    void twoFieldIndex() {
        compareCodeBlockResult(
                "twoFieldIndex",
                "(it) -> order == null ? it.getId() :\n" +
                        "    java.util.Map.<java.lang.String, java.util.function.Function<fake.graphql.example.model.Inventory, java.lang.String>>of(\n" +
                        "        \"STORE_ID_FILM_ID\", type -> type.getStoreId() + \",\" + type.getFilmId()\n" +
                        "    ).get(order.getOrderByField().toString()).apply(it)",
                PAGE_INFO, ORDER
        );
    }
}
