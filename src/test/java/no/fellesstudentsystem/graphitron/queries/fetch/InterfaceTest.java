package no.fellesstudentsystem.graphitron.queries.fetch;

import no.fellesstudentsystem.graphitron.common.GeneratorTest;
import no.fellesstudentsystem.graphitron.common.configuration.SchemaComponent;
import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationTarget;
import no.fellesstudentsystem.graphitron.generators.abstractions.ClassGenerator;
import no.fellesstudentsystem.graphitron.reducedgenerators.InterfaceOnlyFetchDBClassGenerator;
import no.fellesstudentsystem.graphql.directives.GenerationDirective;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.fellesstudentsystem.graphitron.common.configuration.SchemaComponent.NODE;
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
        assertGeneratedContentContains(
                "manyFields",
                "select.optional(\"first\", CUSTOMER.FIRST_NAME)",
                "select.optional(\"last\", CUSTOMER.LAST_NAME)"
        );
    }

    @Test
    @DisplayName("Type implements multiple interfaces")
    void twoInterfaces() {
        assertGeneratedContentContains(
                "twoInterfaces",
                "loadCustomerByFirstNamesAsNamed",
                ",Set<String> firstNames,",
                ".where(CUSTOMER.FIRST_NAME.in(firstNames))",
                "loadCustomerByIdsAsNode",
                ",Set<String> ids,",
                ".where(CUSTOMER.hasIds(ids))"
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
}
