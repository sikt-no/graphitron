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
                "customer = customerTableMethod.customerTable(_a_customer)",
                ".from(_a_customer)"
        );
    }

    @Test
    @DisplayName("One argument")
    void withOneArgument() {
        assertGeneratedContentContains("withOneArgument" ,
                "customerTableMethod = new CustomerTableMethod()",
                "customer = customerTableMethod.customerTable(_a_customer, first_name)",
                ".from(_a_customer)"
        );
    }

    @Test
    @DisplayName("With pagination")
    void paginated() {
        assertGeneratedContentContains("paginated" , Set.of(CUSTOMER_CONNECTION),
                "customer = customerTableMethod.customerTable(_a_customer, first_name)"
        );
    }
    @Test
    @DisplayName("On splitQuery")
    void splitQuery() {
        assertGeneratedContentContains("splitQuery" , Set.of(CUSTOMER_CONNECTION),
                "customer = customerTableMethod.customerTable(_a_customer)"
        );
    }

    @Test
    @DisplayName("With reference")
    void reference() {
        assertGeneratedContentContains("reference",
                "customer = CUSTOMER.as(",
                "customer = customerTableMethod.customerTable(_a_customer, first_name)",
                "address_2138977089_staff = _a_customer_2168032777_address.staff().as(",
                "address_2138977089_staff = staffTableMethod.staffTable(_a_address_2138977089_staff)"
        );
    }

    @Test
    @DisplayName("With ContextArgument")
    void withContextArgument() {
        assertGeneratedContentContains("withContextArgument",
                "customer = customerTableMethod.customerTable(_a_customer, _cf_ctxField)",
                "Customer customerForQuery(DSLContext _iv_ctx, String _cf_ctxField"
        );

    }
}
