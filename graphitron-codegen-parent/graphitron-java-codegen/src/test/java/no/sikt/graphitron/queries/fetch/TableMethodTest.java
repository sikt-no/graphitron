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
                "var customerTableMethod = new CustomerTableMethod();",
                        "_customer = customerTableMethod.customerTable(_customer)",
                        ".from(_customer)");
    }

    @Test
    @DisplayName("One argument")
    void withOneArgument() {
        assertGeneratedContentContains("withOneArgument" ,
                "var customerTableMethod = new CustomerTableMethod();",
                "_customer = customerTableMethod.customerTable(_customer, first_name)",
                ".from(_customer)");
    }

    @Test
    @DisplayName("With pagination")
    void paginated() {
        assertGeneratedContentContains("paginated" , Set.of(CUSTOMER_CONNECTION),
                "_customer = customerTableMethod.customerTable(_customer, first_name);");
    }
    @Test
    @DisplayName("On splitQuery")
    void splitQuery() {
        assertGeneratedContentContains("splitQuery" , Set.of(CUSTOMER_CONNECTION),
                "_customer = customerTableMethod.customerTable(_customer);");
    }

    @Test
    @DisplayName("With reference")
    void reference() {
        assertGeneratedContentContains("reference",
                "var _customer = CUSTOMER.as(\"customer_2952383337\");"
                , "_customer = customerTableMethod.customerTable(_customer, first_name);"
                ,"var address_1214171484_staff = customer_2952383337_address.staff().as(\"staff_2623539941\")"
                ,"address_1214171484_staff = staffTableMethod.staffTable(address_1214171484_staff)");
    }

    @Test
    @DisplayName("With ContextArgument")
    void withContextArgument() {
        assertGeneratedContentContains("withContextArgument",
                "_customer = customerTableMethod.customerTable(_customer, _c_ctxField);",
                "public static Customer customerForQuery(DSLContext ctx, String _c_ctxField");

    }
}
