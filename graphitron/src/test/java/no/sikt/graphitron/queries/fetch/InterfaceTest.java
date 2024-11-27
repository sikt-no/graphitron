package no.sikt.graphitron.queries.fetch;

import no.sikt.graphitron.common.GeneratorTest;
import no.sikt.graphitron.common.configuration.SchemaComponent;
import no.sikt.graphitron.definitions.interfaces.GenerationTarget;
import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.reducedgenerators.InterfaceOnlyFetchDBClassGenerator;
import no.sikt.graphql.directives.GenerationDirective;
import no.sikt.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.common.configuration.SchemaComponent.CUSTOMER_TABLE;
import static no.sikt.graphitron.common.configuration.SchemaComponent.NODE;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

@DisplayName("Query interfaces - Interface handling for types implementing interfaces") // Uses Node as default in tests.
public class InterfaceTest extends GeneratorTest {
    @Override
    protected String getSubpath() {
        return "queries/fetch/interfaces";
    }

    @Override
    protected Set<SchemaComponent> getComponents() {
        return makeComponents(NODE);
    }

    @Override
    protected List<ClassGenerator<? extends GenerationTarget>> makeGenerators(ProcessedSchema schema) {
        return List.of(new InterfaceOnlyFetchDBClassGenerator(schema));
    }

    @Test
    @DisplayName("Only ID")
    void defaultCase() {
        assertGeneratedContentMatches("default");
    }

    @Test
    @DisplayName("Multiple fields")
    void manyFields() {
        assertGeneratedContentContains("manyFields", "_customer.FIRST_NAME", "_customer.LAST_NAME");
    }

    @Test
    @DisplayName("Type implements multiple interfaces")
    void twoInterfaces() {
        assertGeneratedContentContains(
                "twoInterfaces",
                "loadCustomerByFirstNamesAsNamed",
                ",Set<String> firstNames,",
                ".where(_customer.FIRST_NAME.in(firstNames))",
                "loadCustomerByIdsAsNode",
                ",Set<String> ids,",
                ".where(_customer.hasIds(ids))"
        );
    }

    @Test
    @DisplayName("Type implements interface but does not set table")
    void withoutTable() {
        assertThatThrownBy(() -> generateFiles("withoutTable"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(String.format("Type Type needs to have the @%s directive set to be able to implement interface Node", GenerationDirective.TABLE.getName()));
    }

    @Test
    @DisplayName("Type implements interface without table set but no queries use the interface")
    void withoutTableAndQuery() {
        assertDoesNotThrow(() -> generateFiles("withoutTableAndQuery"));
    }

    @Test
    @DisplayName("Interface with a reference to a type")
    void interfaceWithType() {
        assertGeneratedContentContains(
                "interfaceWithType",
                Set.of(CUSTOMER_TABLE),
                "_payment.customer()",
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
}
