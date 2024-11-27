package no.sikt.graphitron.queries.fetch;

import no.sikt.graphitron.common.GeneratorTest;
import no.sikt.graphitron.common.configuration.SchemaComponent;
import no.sikt.graphitron.definitions.interfaces.GenerationTarget;
import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.reducedgenerators.MapOnlyFetchDBClassGenerator;
import no.sikt.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.common.configuration.SchemaComponent.*;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    protected List<ClassGenerator<? extends GenerationTarget>> makeGenerators(ProcessedSchema schema) {
        return List.of(new MapOnlyFetchDBClassGenerator(schema));
    }

    @Test
    @DisplayName("No input")
    void defaultCase() {
        assertGeneratedContentMatches("default");
    }

    // Note: Can not handle dates yet, need to make a better type mapping for fields.
    @Test
    @DisplayName("String field")
    void string() {
        assertGeneratedContentMatches("string"); // Check the placement, but just this once.
    }

    @Test
    @DisplayName("ID field")
    void id() {
        assertGeneratedContentContains("id", ", String id,", "_customer.hasId(id)");
    }

    @Test
    @DisplayName("ID field that is not the primary ID")
    void idOther() {
        assertGeneratedContentContains("idOther", "_customer.hasAddressId(id)");
    }

    @Test
    @DisplayName("Boolean field")
    void booleanCase() {
        assertGeneratedContentContains("boolean", ", Boolean bool,", "_customer.ACTIVE.eq(bool)");
    }

    @Test
    @DisplayName("Integer field")
    void integer() {
        assertGeneratedContentContains("integer", ", Integer length,", "_film.LENGTH.eq(length)");
    }

    @Test
    @DisplayName("Field with @field directive")
    void fieldOverride() {
        assertGeneratedContentContains("fieldOverride", ", String name,", "_customer.FIRST_NAME.eq(name)");
    }

    @Test
    @DisplayName("Two string fields")
    void twoFields() {
        assertGeneratedContentContains(
                "twoFields",
                ", String firstName, String lastName,",
                "_customer.FIRST_NAME.eq(firstName)",
                "_customer.LAST_NAME.eq(lastName)"
        );
    }

    @Test
    @DisplayName("Listed field")
    void list() {
        assertGeneratedContentContains(
                "list",
                ", List<String> email,",
                "email.size() > 0 ? _customer.EMAIL.in(email) : DSL.noCondition()"
        );
    }

    @Test  // Special case methods for IDs.
    @DisplayName("ID list field")
    void idList() {
        assertGeneratedContentContains("idList", ", List<String> id,", "_customer.hasIds(id.stream().collect(Collectors.toSet()))");
    }

    @Test
    @DisplayName("ID list field that is not the primary ID")
    void idOtherList() {
        assertGeneratedContentContains("idOtherList", "_customer.hasAddressIds(id.stream().collect(Collectors.toSet()))");
    }

    @Test
    @DisplayName("Input type field")
    void input() {
        assertGeneratedContentContains(
                "input",
                Set.of(DUMMY_INPUT),
                ", DummyInput in,",
                "in.getId() != null ? _customer.hasId(in.getId()) : DSL.noCondition()"
        );
    }

    @Test
    @DisplayName("Nested input field")
    void nestedInput() {
        assertGeneratedContentContains(
                "nestedInput",
                Set.of(DUMMY_INPUT),
                ", Wrapper in,",
                "in.getIn().getId() != null ? _customer.hasId(in.getIn().getId()) : DSL.noCondition()"
        );
    }

    @Test
    @DisplayName("Nested and then listed input field with two inner fields")
    void nestedListedInputTwoFields() {
        assertGeneratedContentContains(
                "nestedListedInputTwoFields",
                "in.getIn().size() > 0 ?" +
                        "DSL.row(_customer.FIRST_NAME, _customer.LAST_NAME).in(" +
                        "        in.getIn().stream().map(internal_it_ ->" +
                        "                DSL.row(DSL.inline(internal_it_.getFirst()), DSL.inline(internal_it_.getLast()))" +
                        "        ).collect(Collectors.toList())" +
                        ") : DSL.noCondition()"
                );
    }

    @Test
    @DisplayName("Listed, then input nested and listed again field") // Could equivalently be input as well, but field is simpler.
    void listedNestedListedField() {
        assertThatThrownBy(() -> getProcessedSchema("listedNestedListedField"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(
                        "Argument 'in0' is a collection of InputFields ('Wrapper') type." +
                                " Fields returning collections: 'in1' are not supported on such types (used for generating condition tuples)"
                );
    }

   @Test
   @DisplayName("Three-level input type containing two other input types on the same level")
   void multiLevelInput() {
        assertGeneratedContentContains(
                "multiLevelInput", Set.of(STAFF, NAME_INPUT),
                ".where(_staff.FIRST_NAME.eq(staff.getInfo().getName().getFirstname()))" +
                ".and(_staff.LAST_NAME.eq(staff.getInfo().getName().getLastname()))" +
                ".and(staff.getInfo().getJobEmail().getEmail() != null ? _staff.EMAIL.eq(staff.getInfo().getJobEmail().getEmail()) : DSL.noCondition())" +
                ".and(_staff.ACTIVE.eq(staff.getActive()))" +
                ".orderBy"
        );
   }
}
