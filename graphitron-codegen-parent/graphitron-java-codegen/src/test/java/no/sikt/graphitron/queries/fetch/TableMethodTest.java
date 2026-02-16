package no.sikt.graphitron.queries.fetch;

import no.sikt.graphitron.common.GeneratorTest;
import no.sikt.graphitron.configuration.externalreferences.ExternalReference;
import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.generators.db.DBClassGenerator;
import no.sikt.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.common.configuration.ReferencedEntry.CONTEXT_SERVICE;
import static no.sikt.graphitron.common.configuration.SchemaComponent.CUSTOMER_CONNECTION;

@DisplayName("TableMethod for queries")
public class TableMethodTest extends GeneratorTest {
    @Override
    protected String getSubpath() {
        return "queries/fetch/tableMethod";
    }

    @Override
    protected List<ClassGenerator> makeGenerators(ProcessedSchema schema) {
        return List.of(new DBClassGenerator(schema));
    }

    @Override
    protected Set<ExternalReference> getExternalReferences() {
        return makeReferences(CONTEXT_SERVICE);
    }

    @Test
    @DisplayName("No extra arguments")
    void noExtraArguments() {
        assertGeneratedContentContains("noExtraArguments",
                "customerTableMethod = new CustomerTableMethod()",
                "_a_customer = _rs_customerTableMethod.customerTable(CUSTOMER.as(\"customer_2168032777\"))",
                ".from(_a_customer)"
        );
    }

    @Test
    @DisplayName("One argument")
    void withOneArgument() {
        assertGeneratedContentContains("withOneArgument" ,
                "customerTableMethod = new CustomerTableMethod()",
                "_a_customer = _rs_customerTableMethod.customerTable(CUSTOMER.as(\"customer_2168032777\"), _mi_first_name)",
                ".from(_a_customer)"
        );
    }

    @Test
    @DisplayName("One argument in subquery")
    void withOneArgumentInSubquery() {
        assertGeneratedContentContains("withOneArgumentInSubquery",
                "customerForQuery_customer_address(_mi_first_name)",
                "customerForQuery_customer_address(String _mi_first_name)"
        );
    }

    @Test
    @DisplayName("With pagination")
    void paginated() {
        assertGeneratedContentContains("paginated", Set.of(CUSTOMER_CONNECTION),
                """
                var _a_customer = _rs_customerTableMethod.customerTable(CUSTOMER.as("customer_2168032777"), _mi_first_name);
                var _iv_orderFields = _a_customer.fields(_a_customer.getPrimaryKey().getFieldsArray());
                return _iv_ctx
                        .select(
                                QueryHelper.getOrderByToken(_a_customer, _iv_orderFields),
                                customerForQuery_customerTable(_mi_first_name)
                        )
                """, """
                private static SelectField<CustomerTable> customerForQuery_customerTable(String _mi_first_name) {
                        var _rs_customerTableMethod = new CustomerTableMethod();
                        var _a_customer = _rs_customerTableMethod.customerTable(CUSTOMER.as("customer_2168032777"), _mi_first_name);
                        return DSL.row(_a_customer.getId()).mapping(Functions.nullOnAllNull(CustomerTable::new));""");
    }

    @Test
    @DisplayName("On splitQuery")
    void splitQuery() {
        assertGeneratedContentContains("splitQuery", Set.of(CUSTOMER_CONNECTION),
                "_a_customer = _rs_customerTableMethod.customerTable(CUSTOMER.as(\"customer_2168032777\")); return _iv_ctx.select",
                "_a_customer = _rs_customerTableMethod.customerTable(CUSTOMER.as(\"customer_2168032777\")); return DSL.row("
        );
    }

    @Test
    @DisplayName("With reference")
    void reference() {
        assertGeneratedContentContains("reference",
                "customer = _rs_customerTableMethod.customerTable(CUSTOMER.as(\"customer_2168032777\"), _mi_first_name)",
                "address_2138977089_staff = _rs_staffTableMethod.staffTable(_a_customer_2168032777_address.staff().as(\"staff_4083961958\"))"
        );
    }

    @Test
    @DisplayName("With ContextArgument")
    void withContextArgument() {
        assertGeneratedContentContains("withContextArgument",
                "Customer customerForQuery(DSLContext _iv_ctx, String _cf_ctxField",
                "customer = _rs_customerTableMethod.customerTable(CUSTOMER.as(\"customer_2168032777\"), _cf_ctxField);" +
                "return _iv_ctx.",
                "SelectField<Customer> customerForQuery_customer(String _cf_ctxField) {",
                "customer = _rs_customerTableMethod.customerTable(CUSTOMER.as(\"customer_2168032777\"), _cf_ctxField);" +
                "return DSL.row(_a_customer."
        );
    }
}
