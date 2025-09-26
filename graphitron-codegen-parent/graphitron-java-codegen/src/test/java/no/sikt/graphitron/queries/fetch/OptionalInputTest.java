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

import static no.sikt.graphitron.common.configuration.SchemaComponent.CUSTOMER_TABLE;
import static no.sikt.graphitron.common.configuration.SchemaComponent.DUMMY_INPUT;

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
    protected List<ClassGenerator> makeGenerators(ProcessedSchema schema) {
        return List.of(new MapOnlyFetchDBClassGenerator(schema), new InterfaceOnlyFetchDBClassGenerator(schema));
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

    @Test // Note that if the inner input type is not marked as required, an exception will be thrown wrongly stating that the outer field is the culprit.
    @DisplayName("Listed and then nested input field")
    void listedNestedInput() {
        assertGeneratedContentContains(
                "listedNestedInput", Set.of(DUMMY_INPUT),
                "_customer.hasId(in0.get(internal_it_).getIn1().getId())"
                );
    }

    @Test
    @DisplayName("Nested and then listed input field")
    void nestedListedInput() {
        assertGeneratedContentContains(
                "nestedListedInput", Set.of(DUMMY_INPUT),
                "_customer.hasId(in.getIn().get(internal_it_).getId())"
        );
    }
}
