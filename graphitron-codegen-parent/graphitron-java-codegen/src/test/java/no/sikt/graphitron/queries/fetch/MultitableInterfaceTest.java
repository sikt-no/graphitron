package no.sikt.graphitron.queries.fetch;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static no.sikt.graphitron.common.configuration.SchemaComponent.CUSTOMER_TABLE;

public class MultitableInterfaceTest extends InterfaceTest {

    // Disabled until GGG-104
    @Override
    protected boolean validateSchema() {
        return false;
    }

    @Override
    protected String getSubpath() {
        return super.getSubpath() + "/multitableInterface";
    }

    @Test
    @DisplayName("Default case")
    void defaultCase() {
        assertGeneratedContentMatches("default");
    }

    @Test
    @DisplayName("No implementations")
    void noImplementations() {
        assertGeneratedContentContains("noImplementations", ", SelectionSet select) {return null;}");
    }

    @Test
    @DisplayName("One implementation")
    void oneImplementation() {
        assertGeneratedContentMatches("oneImplementation");
    }

    @Test
    @DisplayName("Two implementations")
    void twoImplementations() {
        assertGeneratedContentMatches("twoImplementations");
    }

    @Test
    @DisplayName("Multiple implementations")
    void multipleImplementations() {
        assertGeneratedContentContains(
                "multipleImplementations",
                "citySortFieldsForSomeInterface().unionAll(customerSortFieldsForSomeInterface().unionAll(addressSortFieldsForSomeInterface()))",
                "mappedAddress.field(\"$data\").as(\"$dataForAddress\")," +
                        "mappedCustomer.field(\"$data\").as(\"$dataForCustomer\")," +
                        "mappedCity.field(\"$data\").as(\"$dataForCity\")",
                "case \"Address\": return internal_it_.get(\"$dataForAddress\", SomeInterface.class)",
                "case \"Customer\": return internal_it_.get(\"$dataForCustomer\"",
                "case \"City\": return internal_it_.get(\"$dataForCity\""
        );
    }

    @Test
    @DisplayName("Interface with a reference to a type")
    void interfaceWithType() {
        assertGeneratedContentContains(
                "interfaceWithType",
                Set.of(CUSTOMER_TABLE),
                " payment_425747824_customer = _payment.customer()",
                ".from(payment_425747824_customer",
                "CustomerTable::new"
        );
    }

    @Test
    @Disabled("Disabled until alwaysUsePrimaryKeyInSplitQueries-property is removed.")
    @DisplayName("Interface with type reference with splitQuery should have reference key fields in subquery")
    void interfaceWithTypeSplitQuery() {
        assertGeneratedContentContains(
                "interfaceWithTypeSplitQuery",
                Set.of(CUSTOMER_TABLE),
                "row(DSL.row(_payment.CUSTOMER_ID), _payment.getId()).mapping"
        );
    }

    @Test
    @DisplayName("Interface with type reference with splitQuery should have primary key fields in subquery")
    void interfaceWithTypeSplitQueryOnlyPrimaryKey() {
        assertGeneratedContentContains(
                "interfaceWithTypeSplitQuery",
                Set.of(CUSTOMER_TABLE),
                "row(DSL.row(_payment.PAYMENT_ID), _payment.getId()).mapping"
        );
    }

    @Test
    @DisplayName("Listed without pagination")
    void listed() {
        assertGeneratedContentMatches("listed");
    }


    @Test
    @DisplayName("Input on multitable interface")
    void withInput() {
        assertGeneratedContentMatches("withInput");
    }

    @Test
    @DisplayName("Paginated")
    void paginated() {
        assertGeneratedContentMatches("paginated");
    }

    @Test
    @DisplayName("Paginated with input")
    void paginatedWithInput() {
        assertGeneratedContentContains("paginatedWithInput",
                "SortFieldsForPayments(pageSize, _token, customerId).union",
                ".where(customerId != null ? _paymentp2007_02.CUSTOMER_ID.eq(customerId) : DSL.noCondition()).and(_token"
        );
    }

    @Test
    @DisplayName("Multiple inputs on multitable interface")
    void withMultipleInputs() {
        assertGeneratedContentContains("withMultipleInputs",
                "paymenttypetwoSortFieldsForPayments(customerId, staff)" +
                        ".unionAll(paymenttypeoneSortFieldsForPayments(customerId, staff))",
                "SortFieldsForPayments(String customerId, PaymentStaffInput staff)",
                ".where(customerId != null ? _paymentp2007_01.",
                ".and(_paymentp2007_01.STAFF_ID.eq(staff.getStaffId()))"
                );
    }

    @Test
    @DisplayName("Condition on multi table interface")
    void withQueryCondition() {
        assertGeneratedContentContains("withQueryCondition",
                ".from(_paymentp2007_01).where(no.sikt.",
                ".from(_paymentp2007_02).where(no.sikt."
        );
    }

    @Test
    @DisplayName("Condition on input on multi table interface")
    void withInputCondition() {
        assertGeneratedContentContains("withInputCondition",
                ".from(_paymentp2007_01).where(customerId",
                ": DSL.noCondition()).and(no.sikt",
                "QueryPaymentInterfaceCondition.payments(_paymentp2007_01, customerId)"
        );
    }

    @Test
    @DisplayName("Listed multitable interface in splitQuery field")
    void listedInSplitQuery() {
        assertGeneratedContentMatches("splitQueryListed");
    }

    @Test
    @DisplayName("Paginated multitable interface in splitQuery field")
    void paginatedInSplitQuery() {
        assertGeneratedContentMatches("splitQueryPaginated");
    }

    @Test
    @DisplayName("Multitable interface in splitQuery field with input")
    void splitQueryWithInput() {
        assertGeneratedContentContains("splitQueryWithInput",
                ".from(payment_425747824_customer).where(payment_425747824_customer.EMAIL.eq(email))",
                ".from(_payment).where(DSL.row(_payment.PAYMENT_ID).in(paymentResolverKeys)).fetch"
                );
    }
}
