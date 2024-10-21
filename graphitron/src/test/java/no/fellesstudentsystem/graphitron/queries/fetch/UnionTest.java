package no.fellesstudentsystem.graphitron.queries.fetch;

import no.fellesstudentsystem.graphitron.common.GeneratorTest;
import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationTarget;
import no.fellesstudentsystem.graphitron.generators.abstractions.ClassGenerator;
import no.fellesstudentsystem.graphitron.reducedgenerators.MapOnlyFetchDBClassGenerator;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.fellesstudentsystem.graphitron.common.configuration.SchemaComponent.*;

@DisplayName("Query outputs - Union types")
public class UnionTest extends GeneratorTest {
    @Override
    protected String getSubpath() {
        return "queries/fetch/union";
    }

    @Override
    protected List<ClassGenerator<? extends GenerationTarget>> makeGenerators(ProcessedSchema schema) {
        return List.of(new MapOnlyFetchDBClassGenerator(schema));
    }

    @Test // Note that setting it as a list does nothing, so will not add listed tests.
    @DisplayName("Union with one component")
    void defaultCase() {
        assertGeneratedContentMatches("default", CUSTOMER_UNION);
    }

    @Test
    @DisplayName("Union next to an unrelated field")
    void withOtherField() {
        assertGeneratedContentContains("withOtherField", Set.of(CUSTOMER_QUERY), "(a0, a1_0) -> new Customer(a0, a1_0)");
    }

    @Test
    @DisplayName("Union with two components")
    void twoComponents() {
        assertGeneratedContentContains(
                "twoComponents", Set.of(CUSTOMER_UNION),
                "CustomerUnion0::new",
                "CustomerUnion1::new",
                "(a0_0, a0_1) -> new Customer(a0_0 != null ? a0_0 : a0_1)"
        );
    }

    @Test
    @DisplayName("Union with two components next to an unrelated field")
    void twoComponentsWithOtherField() {
        assertGeneratedContentContains(
                "twoComponentsWithOtherField", Set.of(CUSTOMER_QUERY),
                "(a0, a1_0, a1_1) -> new Customer(a0, a1_0 != null ? a1_0 : a1_1)"
        );
    }

    @Test
    @DisplayName("Union with three components")
    void threeComponents() {
        assertGeneratedContentContains(
                "threeComponents", Set.of(CUSTOMER_UNION),
                "(a0_0, a0_1, a0_2) -> new Customer(a0_0 != null ? a0_0 : a0_1 != null ? a0_1 : a0_2)"
        );
    }

    @Test
    @DisplayName("Union component with two fields")
    void componentWithMultipleFields() {
        assertGeneratedContentContains(
                "componentWithMultipleFields", Set.of(CUSTOMER_UNION),
                ".row(_customer.getId(),_customer.EMAIL).mapping(Functions.nullOnAllNull(CustomerUnion::new)"
        );
    }

    @Test
    @DisplayName("Union on a non-root level")
    void splitQuery() {
        assertGeneratedContentContains("splitQuery", Set.of(SPLIT_QUERY_WRAPPER), "CustomerUnion::new", "(a0_0) -> new Customer(a0_0)");
    }

    @Test
    @DisplayName("Union component with an enum field")
    void componentWithEnumField() {
        assertGeneratedContentContains("componentWithEnumField", Set.of(CUSTOMER_UNION, DUMMY_ENUM), ".row(DSL.row(_customer.ENUM.convert(");
    }
}
