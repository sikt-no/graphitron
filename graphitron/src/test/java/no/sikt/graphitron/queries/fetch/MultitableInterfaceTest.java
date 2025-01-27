package no.sikt.graphitron.queries.fetch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static no.sikt.graphitron.common.configuration.SchemaComponent.CUSTOMER_TABLE;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class MultitableInterfaceTest extends InterfaceTest {
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
    @DisplayName("Interface with type reference with splitQuery should not have CustomerTable in subquery")
    void interfaceWithTypeSplitQuery() {
        assertGeneratedContentContains(
                "interfaceWithTypeSplitQuery",
                Set.of(CUSTOMER_TABLE),
                "row(_payment.getId()).mapping"
        );
    }

    @Test
    @DisplayName("Query on root has an implementation without table set should throw exception")
    void withoutTableFromRoot() {
        assertThatThrownBy(() -> generateFiles("withoutTableFromRoot"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Interface 'SomeInterface' is returned in field 'someInterface', but " +
                        "type 'Customer' implementing 'SomeInterface' does not have table set. This is not supported.");
    }

    @Test
    @DisplayName("Listed without pagination")
    void listed() {
        assertGeneratedContentMatches("listed");
    }


    @Test
    @DisplayName("Paginated")
    void paginated() {
        assertGeneratedContentMatches("paginated");
    }

    /*
     * Temporary validation tests
     * */

    @Test
    @DisplayName("Input on interface is not currently supported")
    void withInput() {
        assertThatThrownBy(() -> generateFiles("withInput"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Input fields on fields returning interfaces is currently only supported for single table interfaces. " +
                        "Field 'someInterface' returning interface 'SomeInterface' has one or more input field(s).");
    }

    @Test
    @DisplayName("Condition on interface is not currently supported")
    void withCondition() {
        assertThatThrownBy(() -> generateFiles("withCondition"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Conditions on fields returning interfaces is currently only " +
                        "supported for single table interfaces. Field 'someInterface' returning interface 'SomeInterface' has condition."
                );
    }

    @Test
    @DisplayName("Interface returned in field has an implementing type with table missing primary key")
    void listedNoPrimarykey() {
        assertThatThrownBy(() -> generateFiles("listedNoPrimaryKey"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Interface 'SomeInterface' is returned in field 'query', but implementing " +
                        "type 'PgUserMapping' has table 'PG_USER_MAPPING' which does not have a primary key.");
    }
}
