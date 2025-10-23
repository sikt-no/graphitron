package no.sikt.graphitron.queries.fetch;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static no.sikt.graphitron.common.configuration.SchemaComponent.*;

@DisplayName("Multi-table interfaces - Queries")
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
        assertGeneratedContentContains("noImplementations", ", SelectionSet _iv_select) {return null;}");
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
                "mappedAddress.field(\"$data\")," +
                        "mappedCustomer.field(\"$data\")," +
                        "mappedCity.field(\"$data\")",
                "case \"Address\" -> (SomeInterface) _iv_e1",
                "case \"Customer\" -> (SomeInterface) _iv_e2",
                "case \"City\" -> (SomeInterface) _iv_e3"
        );
    }

    @Test
    @DisplayName("Interface with a reference to a type")
    void interfaceWithType() {
        assertGeneratedContentContains(
                "interfaceWithType",
                Set.of(CUSTOMER_TABLE),
                "payment_1831371789_customer = _a_payment.customer()",
                ".from(_a_payment_1831371789_customer",
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
                "row(DSL.row(_a_payment.CUSTOMER_ID), _a_payment.getId()).mapping"
        );
    }

    @Test
    @DisplayName("Interface with type reference with splitQuery should have primary key fields in subquery")
    void interfaceWithTypeSplitQueryOnlyPrimaryKey() {
        assertGeneratedContentContains(
                "interfaceWithTypeSplitQuery",
                Set.of(CUSTOMER_TABLE),
                "row(DSL.row(_a_payment.PAYMENT_ID), _a_payment.getId()).mapping"
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
                "SortFieldsForPayments(_iv_pageSize, _iv_token, customerId).union",
                ".where(customerId != null ? _a_paymentp2007_02.CUSTOMER_ID.eq(customerId) : DSL.noCondition()).and(_iv_token"
        );
    }

    @Test
    @DisplayName("Multiple inputs on multitable interface")
    void withMultipleInputs() {
        assertGeneratedContentContains("withMultipleInputs",
                "paymenttypetwoSortFieldsForPayments(customerId, staff)" +
                        ".unionAll(paymenttypeoneSortFieldsForPayments(customerId, staff))",
                "SortFieldsForPayments(String customerId, PaymentStaffInput staff)",
                ".where(customerId != null ? _a_paymentp2007_01.",
                ".and(_a_paymentp2007_01.STAFF_ID.eq(staff.getStaffId()))"
        );
    }

    @Test
    @DisplayName("Condition on multi table interface")
    void withQueryCondition() {
        assertGeneratedContentContains("withQueryCondition",
                ".from(_a_paymentp2007_01).where(no.sikt.",
                ".from(_a_paymentp2007_02).where(no.sikt."
        );
    }

    @Test
    @DisplayName("Condition on input on multi table interface")
    void withInputCondition() {
        assertGeneratedContentContains("withInputCondition",
                ".from(_a_paymentp2007_01).where(customerId",
                ": DSL.noCondition()).and(no.sikt",
                "QueryPaymentInterfaceCondition.payments(_a_paymentp2007_01, customerId)"
        );
    }

    @Test
    @DisplayName("Multitable interface in splitQuery field")
    void splitQuery() {
        assertGeneratedContentMatches("splitQuery", PERSON_WITH_EMAIL);
    }

    @Test
    @DisplayName("Listed multitable interface in splitQuery field")
    void listedInSplitQuery() {
        assertGeneratedContentContains("splitQueryListed", Set.of(PERSON_WITH_EMAIL),
                "DSL.multiset(DSL.select(DSL.row(unionKeysQuery.",
                ".fetchMap(_iv_r -> _iv_r.value1().valuesRow(), _iv_r -> _iv_r.value2().map(Record1::value1)",
                "unionKeysQuery.field(\"$innerRowNum\")))).from" // Make sure there's no limit
        );
    }

    @Test
    @DisplayName("Paginated multitable interface in splitQuery field")
    void paginatedInSplitQuery() {
        assertGeneratedContentMatches("splitQueryPaginated", PERSON_WITH_EMAIL_CONNECTION);
    }

    @Test
    @DisplayName("Multitable interface in splitQuery field with input")
    void splitQueryWithInput() {
        assertGeneratedContentContains("splitQueryWithInput", Set.of(PERSON_WITH_EMAIL_CONNECTION),
                ".from(_a_payment_1831371789_customer).where(_a_payment_1831371789_customer.EMAIL.eq(email))",
                ".from(_a_payment).where(DSL.row(_a_payment.PAYMENT_ID).in(_rk_payment)).fetch"
        );
    }
}
