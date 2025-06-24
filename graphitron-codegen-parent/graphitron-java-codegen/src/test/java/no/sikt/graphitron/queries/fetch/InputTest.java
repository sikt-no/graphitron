package no.sikt.graphitron.queries.fetch;

import no.sikt.graphitron.common.GeneratorTest;
import no.sikt.graphitron.common.configuration.SchemaComponent;
import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.reducedgenerators.InterfaceOnlyFetchDBClassGenerator;
import no.sikt.graphitron.reducedgenerators.MapOnlyFetchDBClassGenerator;
import no.sikt.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.common.configuration.SchemaComponent.*;

@DisplayName("Query inputs - Equality, list and null checks for fields")
public class InputTest extends GeneratorTest {
    @Override
    protected String getSubpath() {
        return "queries/fetch/inputs/required";
    }

    @Override
    protected Set<SchemaComponent> getComponents() {
        return makeComponents(CUSTOMER_TABLE);
    }

    @Override
    protected List<ClassGenerator> makeGenerators(ProcessedSchema schema) {
        return List.of(new MapOnlyFetchDBClassGenerator(schema), new InterfaceOnlyFetchDBClassGenerator(schema));
    }

    @Test
    @DisplayName("No input")
    void defaultCase() {
        assertGeneratedContentMatches("default");
    }

    @Test
    @DisplayName("String field")
    void string() {
        assertGeneratedContentMatches("string"); // Check the placement, but just this once.
    }

    @Test
    @DisplayName("Scalar field found in extended scalars")
    void scalar() {
        assertGeneratedContentContains("scalar", ", LocalDate createdDate,", "customer.CREATE_DATE.eq(createdDate)");
    }

    @Test
    @DisplayName("ID field")
    void id() {
        assertGeneratedContentContains("id", ", String id,", "customer.hasId(id)");
    }

    @Test
    @DisplayName("ID field that is not the primary ID")
    void idOther() {
        assertGeneratedContentContains("idOther", "customer.hasAddressId(id)");
    }

    @Test
    @DisplayName("Boolean field")
    void booleanCase() {
        assertGeneratedContentContains("boolean", ", Boolean bool,", "customer.ACTIVE.eq(bool)");
    }

    @Test
    @DisplayName("Integer field")
    void integer() {
        assertGeneratedContentContains("integer", ", Integer length,", "film.LENGTH.eq(length)");
    }

    @Test
    @DisplayName("Field with @field directive")
    void fieldOverride() {
        assertGeneratedContentContains("fieldOverride", ", String name,", "customer.FIRST_NAME.eq(name)");
    }

    @Test
    @DisplayName("Two string fields")
    void twoFields() {
        assertGeneratedContentContains(
                "twoFields",
                ", String firstName, String lastName,",
                "customer.FIRST_NAME.eq(firstName)",
                "customer.LAST_NAME.eq(lastName)"
        );
    }

    @Test
    @DisplayName("Listed field")
    void list() {
        assertGeneratedContentContains(
                "list",
                ", List<String> email,",
                "email.size() > 0 ? _a_customer.EMAIL.in(email) : DSL.noCondition()"
        );
    }

    @Test  // Special case methods for IDs.
    @DisplayName("ID list field")
    void idList() {
        assertGeneratedContentContains("idList", ", List<String> id,", "customer.hasIds(id.stream().collect(Collectors.toSet()))");
    }

    @Test
    @DisplayName("ID list field that is not the primary ID")
    void idOtherList() {
        assertGeneratedContentContains("idOtherList", "customer.hasAddressIds(id.stream().collect(Collectors.toSet()))");
    }

    @Test
    @DisplayName("Input type field")
    void input() {
        assertGeneratedContentContains(
                "input",
                Set.of(DUMMY_INPUT),
                ", DummyInput in,",
                "in.getId() != null ? _a_customer.hasId(in.getId()) : DSL.noCondition()"
        );
    }

    @Test
    @DisplayName("Nested input field")
    void nestedInput() {
        assertGeneratedContentContains(
                "nestedInput",
                Set.of(DUMMY_INPUT),
                ", Wrapper in,",
                "in.getIn().getId() != null ? _a_customer.hasId(in.getIn().getId()) : DSL.noCondition()"
        );
    }

    @Test
    @DisplayName("Nested and then listed input field with two inner fields")
    void nestedListedInputTwoFields() {
        assertGeneratedContentContains(
                "nestedListedInputTwoFields",
                "customer.FIRST_NAME, _a_customer.LAST_NAME",
                "DSL.val(in.getIn().get(_iv_it).getFirst()), DSL.val(in.getIn().get(_iv_it).getLast())"
        );
    }

   @Test
   @DisplayName("Three-level input type containing two other input types on the same level")
   void multiLevelInput() {
        assertGeneratedContentContains(
                "multiLevelInput", Set.of(STAFF, NAME_INPUT),
                ".where(_a_staff.FIRST_NAME.eq(staff.getInfo().getName().getFirstname()))" +
                ".and(_a_staff.LAST_NAME.eq(staff.getInfo().getName().getLastname()))" +
                ".and(staff.getInfo().getJobEmail().getEmail() != null ? _a_staff.EMAIL.eq(staff.getInfo().getJobEmail().getEmail()) : DSL.noCondition())" +
                ".and(_a_staff.ACTIVE.eq(staff.getActive()))" +
                ".orderBy"
        );
   }

    @Test
    @DisplayName("On field returning single table interface")
    void onSingleTableInterface() {
        assertGeneratedContentContains("onSingleTableInterface",
                ", AddressInput filter",
                ".and(_a_address.POSTAL_CODE.eq(filter.getPostalCode()))");
    }

    /**
     * Given that A has a field referencing B, and this field has a scalar input parameter, and there is no direct
     * relation from A to B but an inverse relation from B to A exists, when a new resolver is generated, a JOIN clause
     * and a WHERE clause should be created. The JOIN clause must include table B retrieved through the inverse
     * relation, and the WHERE clause must include the scalar condition on B.
     */
    @Test
    @DisplayName("SplitQuery field")
    void onSplitQueryField() {
        assertGeneratedContentContains("onSplitQueryField",
                  "_address = ADDRESS.as",
                 "address_2030472956_customer = _address.customer().as",
                 "orderFields = address_2030472956_customer.fields(address_2030472956_customer.getPrimaryKey().getFieldsArray())",
                 """
                 .select(
                        DSL.row(_address.ADDRESS_ID),
                        DSL.row(address_2030472956_customer.getId()).mapping(Functions.nullOnAllNull(CustomerTable::new))
                )
                .from(_address)
                .join(address_2030472956_customer)
                .where(DSL.row(_address.ADDRESS_ID).in(addressResolverKeys.stream().map(Record1::valuesRow).toList()))
                .and(address_2030472956_customer.EMAIL.eq(email))
                .fetchMap(Record2::value1, Record2::value2);
                """
        );
    }
}
