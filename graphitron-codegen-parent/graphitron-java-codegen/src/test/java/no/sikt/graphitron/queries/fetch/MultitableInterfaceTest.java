package no.sikt.graphitron.queries.fetch;

import no.sikt.graphitron.validation.InvalidSchemaException;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static no.sikt.graphitron.common.configuration.SchemaComponent.*;
import static no.sikt.graphitron.generators.db.FetchMultiTableDBMethodGenerator.MSG_ERROR_NO_TABLE;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
                "address.field(\"$data\")," +
                        "_sjs_customer.field(\"$data\")," +
                        "_sjs_city.field(\"$data\")",
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
                "SortFieldsForPayments(_iv_pageSize, _iv_token, _mi_customerId).union",
                ".where(_mi_customerId != null ? _a_paymentp2007_02.CUSTOMER_ID.eq(_mi_customerId) : DSL.noCondition()).and(_iv_token"
        );
    }

    @Test
    @DisplayName("Multiple inputs on multitable interface")
    void withMultipleInputs() {
        assertGeneratedContentContains("withMultipleInputs",
                "paymenttypetwoSortFieldsForPayments(_mi_customerId, _mi_staff)" +
                        ".unionAll(paymenttypeoneSortFieldsForPayments(_mi_customerId, _mi_staff))",
                "SortFieldsForPayments(String _mi_customerId, PaymentStaffInput _mi_staff)",
                ".where(_mi_customerId != null ? _a_paymentp2007_01.",
                ".and(_a_paymentp2007_01.STAFF_ID.eq(_mi_staff.getStaffId()))"
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
                ".from(_a_paymentp2007_01).where(_mi_customerId",
                ": DSL.noCondition()).and(no.sikt",
                "QueryPaymentInterfaceCondition.payments(_a_paymentp2007_01, _mi_customerId)"
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
        assertGeneratedContentMatches("splitQueryListed", PERSON_WITH_EMAIL);
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
                ".from(_a_payment_1831371789_customer).where(_a_payment_1831371789_customer.EMAIL.eq(_mi_email))",
                ".from(_a_payment).where(DSL.row(_a_payment.PAYMENT_ID).in(_rk_payment)).fetch"
        );
    }

    @Test
    @DisplayName("Type implements multitable interface but has no table")
    void implementationWithoutTable() {
        assertThatThrownBy(() -> generateFiles("implementationWithoutTable"))
                .isInstanceOf(InvalidSchemaException.class)
                .hasMessage("Problems have been found that prevent code generation: \n" + String.format(MSG_ERROR_NO_TABLE, "Customer", "SomeInterface"));
    }

    @Test
    @DisplayName("Two types implement multitable interface but have no tables")
    void twoImplementationsWithoutTable() {
        assertThatThrownBy(() -> generateFiles("twoImplementationsWithoutTable"))
                .isInstanceOf(InvalidSchemaException.class)
                .hasMessage("Problems have been found that prevent code generation: \n" + String.format(MSG_ERROR_NO_TABLE, "Address', 'Customer", "SomeInterface"));
    }
}
