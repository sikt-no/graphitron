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
        assertGeneratedContentContains("scalar", ", LocalDate _mi_createdDate,", "customer.CREATE_DATE.eq(_mi_createdDate)");
    }

    @Test
    @DisplayName("ID field")
    void id() {
        assertGeneratedContentContains("id", ", String _mi_id,", "customer.hasId(_mi_id)");
    }

    @Test
    @DisplayName("ID field that is not the primary ID")
    void idOther() {
        assertGeneratedContentContains("idOther", "customer.hasAddressId(_mi_id)");
    }

    @Test
    @DisplayName("Boolean field")
    void booleanCase() {
        assertGeneratedContentContains("boolean", ", Boolean _mi_bool,", "customer.ACTIVE.eq(_mi_bool)");
    }

    @Test
    @DisplayName("Integer field")
    void integer() {
        assertGeneratedContentContains("integer", ", Integer _mi_length,", "film.LENGTH.eq(_mi_length)");
    }

    @Test
    @DisplayName("Field with @field directive")
    void fieldOverride() {
        assertGeneratedContentContains("fieldOverride", ", String _mi_name,", "customer.FIRST_NAME.eq(_mi_name)");
    }

    @Test
    @DisplayName("Two string fields")
    void twoFields() {
        assertGeneratedContentContains(
                "twoFields",
                ", String _mi_firstName, String _mi_lastName,",
                "customer.FIRST_NAME.eq(_mi_firstName)",
                "customer.LAST_NAME.eq(_mi_lastName)"
        );
    }

    @Test
    @DisplayName("Listed field")
    void list() {
        assertGeneratedContentContains(
                "list",
                ", List<String> _mi_email,",
                "email.size() > 0 ? _a_customer.EMAIL.in(_mi_email) : DSL.noCondition()"
        );
    }

    @Test  // Special case methods for IDs.
    @DisplayName("ID list field")
    void idList() {
        assertGeneratedContentContains("idList", ", List<String> _mi_id,", "customer.hasIds(_mi_id.stream().collect(Collectors.toSet()))");
    }

    @Test
    @DisplayName("ID list field that is not the primary ID")
    void idOtherList() {
        assertGeneratedContentContains("idOtherList", "customer.hasAddressIds(_mi_id.stream().collect(Collectors.toSet()))");
    }

    @Test
    @DisplayName("Input type field")
    void input() {
        assertGeneratedContentContains(
                "input",
                Set.of(DUMMY_INPUT),
                ", DummyInput _mi_in,",
                "_mi_in.getId() != null ? _a_customer.hasId(_mi_in.getId()) : DSL.noCondition()"
        );
    }

    @Test
    @DisplayName("Nested input field")
    void nestedInput() {
        assertGeneratedContentContains(
                "nestedInput",
                Set.of(DUMMY_INPUT),
                ", Wrapper _mi_in,",
                "_mi_in.getIn().getId() != null ? _a_customer.hasId(_mi_in.getIn().getId()) : DSL.noCondition()"
        );
    }

    @Test
    @DisplayName("Nested and then listed input field with two inner fields")
    void nestedListedInputTwoFields() {
        assertGeneratedContentContains(
                "nestedListedInputTwoFields",
                "customer.FIRST_NAME, _a_customer.LAST_NAME",
                "DSL.val(_mi_in.getIn().get(_iv_it).getFirst()), DSL.val(_mi_in.getIn().get(_iv_it).getLast())"
        );
    }

    @Test
    @DisplayName("Three-level input type containing two other input types on the same level")
    void multiLevelInput() {
        assertGeneratedContentContains(
                "multiLevelInput", Set.of(STAFF, NAME_INPUT),
                ".where(_a_staff.FIRST_NAME.eq(_mi_staff.getInfo().getName().getFirstname()))" +
                        ".and(_a_staff.LAST_NAME.eq(_mi_staff.getInfo().getName().getLastname()))" +
                        ".and(_mi_staff.getInfo().getJobEmail().getEmail() != null ? _a_staff.EMAIL.eq(_mi_staff.getInfo().getJobEmail().getEmail()) : DSL.noCondition())" +
                        ".and(_a_staff.ACTIVE.eq(_mi_staff.getActive()))" +
                        ".orderBy"
        );
    }

    @Test
    @DisplayName("On field returning single table interface")
    void onSingleTableInterface() {
        assertGeneratedContentContains("onSingleTableInterface",
                ", AddressInput _mi_filter",
                ".and(_a_address.POSTAL_CODE.eq(_mi_filter.getPostalCode()))");
    }

    @Test
    @DisplayName("SplitQuery field")
    void onSplitQueryField() {
        assertGeneratedContentContains("onSplitQueryField",
                ".from(_a_address_223244161_customer).where(_a_address_223244161_customer.EMAIL.eq(_mi_email))",
                ".from(_a_address).where(DSL.row(_a_address.ADDRESS_ID).in(_rk_address)).fetch" // Make sure conditon is not applied on outer query
        );
    }

    @Test
    @DisplayName("Wrapper type that also has input table parameter")
    void wrappedTypeWithInputTable() {
        assertGeneratedContentContains(
                "inputTable",
                // Main method signature includes input record parameter
                "public static FilmContainer filmWrappedWithInputTableAndTableFieldForQuery(DSLContext _iv_ctx,\n" +
                "                    FilmRecord _mi_inputRecord, SelectionSet _iv_select)",
                // Helper method signature receives input record parameter
                "private static SelectField<FilmContainer> filmWrappedWithInputTableAndTableFieldForQuery_filmContainer(\n" +
                "                    FilmRecord _mi_inputRecord)",
                // Nested helper method at depth 1
                "private static SelectField<Language> _1_filmWrappedWithInputTableAndTableFieldForQuery_filmContainer_language()",
                // Main method calls helper with input record parameter
                ".select(filmWrappedWithInputTableAndTableFieldForQuery_filmContainer(_mi_inputRecord))",
                // Helper declares necessary aliases
                "var _a_film = FILM.as(\"film_2185543202\");",
                "var _a_film_2185543202_film = _a_film.film().as(\"film_3535906766\");",
                // Helper calls nested helper
                "DSL.select(_1_filmWrappedWithInputTableAndTableFieldForQuery_filmContainer_language())"
        );
    }
}
