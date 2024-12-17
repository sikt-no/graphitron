package no.sikt.graphitron.queries.fetch;

import no.sikt.graphitron.common.GeneratorTest;
import no.sikt.graphitron.configuration.externalreferences.ExternalReference;
import no.sikt.graphitron.definitions.interfaces.GenerationTarget;
import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.reducedgenerators.InterfaceOnlyFetchDBClassGenerator;
import no.sikt.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.common.configuration.ReferencedEntry.DUMMY_CONDITION;
import static no.sikt.graphitron.common.configuration.SchemaComponent.CUSTOMER_TABLE;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Query interfaces - Interface handling for types implementing interfaces")
public class InterfaceTest extends GeneratorTest {
    @Override
    protected String getSubpath() {
        return "queries/fetch/interfaces/standard";
    }

    @Override
    protected Set<ExternalReference> getExternalReferences() {
        return makeReferences(DUMMY_CONDITION);
    }

    @Override
    protected List<ClassGenerator<? extends GenerationTarget>> makeGenerators(ProcessedSchema schema) {
        return List.of(new InterfaceOnlyFetchDBClassGenerator(schema));
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
                "case \"Address\": return (SomeInterface) internal_it_.get(\"$dataForAddress\")",
                "case \"Customer\": return (SomeInterface) internal_it_.get(\"$dataForCustomer\")",
                "case \"City\": return (SomeInterface) internal_it_.get(\"$dataForCity\")"
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

    /*
    * Temporary validation tests
    * */

    @Test
    @DisplayName("Input on interface is not currently supported")
    void withInput() {
        assertThatThrownBy(() -> generateFiles("withInput"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Input fields on fields returning interfaces is not currently supported. " +
                        "Field 'someInterface' has one or more input field(s).");
    }

    @Test
    @DisplayName("Condition on interface is not currently supported")
    void withCondition() {
        assertThatThrownBy(() -> generateFiles("withCondition"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Conditions on fields returning interfaces is not currently " +
                        "supported. Field 'someInterface' has condition.");
    }

    @Test
    @DisplayName("Paginated fields returning interface is not currently supported")
    void paginated() {
        assertThatThrownBy(() -> generateFiles("paginated"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Fields returning interfaces is currently only supported for " +
                        "lists without pagination. Field 'someInterface' returns a connection type.");
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
