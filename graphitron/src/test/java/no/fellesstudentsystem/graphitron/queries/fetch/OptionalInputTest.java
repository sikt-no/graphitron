package no.fellesstudentsystem.graphitron.queries.fetch;

import no.fellesstudentsystem.graphitron.common.GeneratorTest;
import no.fellesstudentsystem.graphitron.common.configuration.SchemaComponent;
import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationTarget;
import no.fellesstudentsystem.graphitron.generators.abstractions.ClassGenerator;
import no.fellesstudentsystem.graphitron.reducedgenerators.MapOnlyFetchDBClassGenerator;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.fellesstudentsystem.graphitron.common.configuration.SchemaComponent.CUSTOMER_TABLE;
import static no.fellesstudentsystem.graphitron.common.configuration.SchemaComponent.DUMMY_INPUT;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Query optional inputs - Nullability and list checks for optional fields")
public class OptionalInputTest extends GeneratorTest {
    @Override
    protected String getSubpath() {
        return "queries/fetch/inputs/optional";
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
    @DisplayName("Optional field") // Just check that this is placed right.
    void defaultCase() {
        assertGeneratedContentMatches("default");
    }

    @Test
    @DisplayName("Listed field")
    void list() {
        assertGeneratedContentContains("list", "email != null && email.size() > 0 ? _customer.EMAIL.in(email) :DSL.noCondition()");
    }

    @Test
    @DisplayName("Input field")
    void input() {
        assertGeneratedContentContains(
                "input", Set.of(DUMMY_INPUT),
                "in != null && in.getId() != null ? _customer.hasId(in.getId()) : DSL.noCondition()"
        );
    }

    @Test
    @DisplayName("Nested input field")
    void nestedInput() {
        assertGeneratedContentContains(
                "nestedInput", Set.of(DUMMY_INPUT),
                "in != null && in.getIn() != null && in.getIn().getId() != null ? _customer.hasId(in.getIn().getId()) : DSL.noCondition()"
        );
    }

    @Test
    @DisplayName("Listed input field")
    void listedInput() {
        assertThatThrownBy(() -> getProcessedSchema("listedInput", DUMMY_INPUT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(
                        "Argument 'in' is a collection of InputFields ('DummyInput') type." +
                                " Optional fields on such types are not supported." +
                                " The following fields will be treated as mandatory in the resulting, generated condition tuple: 'id'"
                );
    }

    @Test // Note that if the inner input type is not marked as required, an exception will be thrown wrongly stating that the outer field is the culprit.
    @DisplayName("Listed and then nested input field")
    void listedNestedInput() {
        assertGeneratedContentContains(
                "listedNestedInput", Set.of(DUMMY_INPUT),
                "in0 != null && in0.size() > 0 ?" +
                        "        DSL.row(_customer.getId()).in(" +
                        "            in0.stream().map(internal_it_ ->" +
                        "                DSL.row(DSL.inline(internal_it_.getIn1().getId()))" +
                        "            ).collect(Collectors.toList())" +
                        "        ) : DSL.noCondition())"
        );
    }

    @Test
    @DisplayName("Nested and then listed input field")
    void nestedListedInput() {
        assertGeneratedContentContains(
                "nestedListedInput", Set.of(DUMMY_INPUT),
                "in != null && in.getIn() != null && in.getIn().size() > 0 ?" +
                        "        DSL.row(_customer.getId()).in(" +
                        "            in.getIn().stream().map(internal_it_ ->" +
                        "                DSL.row(DSL.inline(internal_it_.getId()))" +
                        "            ).collect(Collectors.toList())" +
                        "        ) : DSL.noCondition())"
        );
    }
}
