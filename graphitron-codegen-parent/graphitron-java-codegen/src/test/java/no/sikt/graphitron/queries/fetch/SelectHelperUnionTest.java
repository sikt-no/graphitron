package no.sikt.graphitron.queries.fetch;

import no.sikt.graphitron.common.GeneratorTest;
import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.reducedgenerators.QueryOnlyHelperDBClassGenerator;
import no.sikt.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.common.configuration.SchemaComponent.*;

@DisplayName("Query outputs - Union type helpers")
public class SelectHelperUnionTest extends GeneratorTest {

    // Disable validation until GGG-104
    @Override
    protected boolean validateSchema() {
        return false;
    }

    @Override
    protected String getSubpath() {
        return "queries/fetch/union";
    }


    @Override
    protected List<ClassGenerator> makeGenerators(ProcessedSchema schema) {
        return List.of(new QueryOnlyHelperDBClassGenerator(schema));
    }

    @Test // Note that setting it as a list does nothing, so will not add listed tests.
    @DisplayName("Union with one component")
    void defaultCase() {
        assertGeneratedContentMatches("default", CUSTOMER_UNION);
    }

    @Test
    @DisplayName("Union next to an unrelated field")
    void withOtherField() {
        assertGeneratedContentContains("withOtherField", Set.of(CUSTOMER_QUERY), "(_iv_e0, _iv_e1_0) -> new Customer(_iv_e0, _iv_e1_0)");
    }

    @Test
    @DisplayName("Union with two components")
    void twoComponents() {
        assertGeneratedContentContains(
                "twoComponents", Set.of(CUSTOMER_UNION),
                "CustomerUnion0::new",
                "CustomerUnion1::new",
                "(_iv_e0_0, _iv_e0_1) -> new Customer(_iv_e0_0 != null ? _iv_e0_0 : _iv_e0_1)"
        );
    }

    @Test
    @DisplayName("Union with two components next to an unrelated field")
    void twoComponentsWithOtherField() {
        assertGeneratedContentContains(
                "twoComponentsWithOtherField", Set.of(CUSTOMER_QUERY),
                "(_iv_e0, _iv_e1_0, _iv_e1_1) -> new Customer(_iv_e0, _iv_e1_0 != null ? _iv_e1_0 : _iv_e1_1)"
        );
    }

    @Test
    @DisplayName("Union with three components")
    void threeComponents() {
        assertGeneratedContentContains(
                "threeComponents", Set.of(CUSTOMER_UNION),
                "(_iv_e0_0, _iv_e0_1, _iv_e0_2) -> new Customer(_iv_e0_0 != null ? _iv_e0_0 : _iv_e0_1 != null ? _iv_e0_1 : _iv_e0_2)"
        );
    }

    @Test
    @DisplayName("Union component with two fields")
    void componentWithMultipleFields() {
        assertGeneratedContentContains(
                "componentWithMultipleFields", Set.of(CUSTOMER_UNION),
                ".row(_a_customer.getId(),_a_customer.EMAIL).mapping(Functions.nullOnAllNull(CustomerUnion::new)"
        );
    }

    @Test
    @DisplayName("Union on a non-root level")
    void splitQuery() {
        assertGeneratedContentContains("splitQuery", Set.of(SPLIT_QUERY_WRAPPER), "CustomerUnion::new", "(_iv_e0_0) -> new Customer(_iv_e0_0)");
    }

    @Test
    @DisplayName("Union component with an enum field")
    void componentWithEnumField() {
        assertGeneratedContentContains("componentWithEnumField", Set.of(CUSTOMER_UNION, DUMMY_ENUM), ".row(DSL.row(_a_customer.ENUM.convert(");
    }
}
