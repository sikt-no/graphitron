package no.fellesstudentsystem.graphitron_newtestorder.code;

import no.fellesstudentsystem.graphitron.generators.resolvers.fetch.FetchResolverMethodGenerator;
import no.fellesstudentsystem.graphitron_newtestorder.GeneratorTest;
import no.fellesstudentsystem.graphitron_newtestorder.SchemaComponent;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static no.fellesstudentsystem.graphitron_newtestorder.SchemaComponent.*;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Resolver sorting - Resolvers with custom ordering")
public class ResolverSortingIDFunctionTest extends GeneratorTest {
    @Override
    protected String getSubpath() {
        return "resolvers/fetch/sorting";
    }

    private String makeFunction(ProcessedSchema schema) {
        return new FetchResolverMethodGenerator(schema.getQueryType(), schema).getIDFunction(schema.getQueryType().getFieldByName("query")).toString();
    }

    private void compareResult(String path, String expected, SchemaComponent... components) {
        assertThat(makeFunction(getProcessedSchema(path, components))).isEqualToIgnoringWhitespace(expected);
    }

    @Test
    @DisplayName("Basic resolver with a sorting parameter")
    public void defaultCase() {
        compareResult(
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
        compareResult(
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
        compareResult(
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
        compareResult(
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
        compareResult(
                "twoFieldIndex",
                "(it) -> order == null ? it.getId() :\n" +
                        "    java.util.Map.<java.lang.String, java.util.function.Function<fake.graphql.example.model.Inventory, java.lang.String>>of(\n" +
                        "        \"STORE_ID_FILM_ID\", type -> type.getStoreId() + \",\" + type.getFilmId()\n" +
                        "    ).get(order.getOrderByField().toString()).apply(it)",
                PAGE_INFO, ORDER
        );
    }
}
