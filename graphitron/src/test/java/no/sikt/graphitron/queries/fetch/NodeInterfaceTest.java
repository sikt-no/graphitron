package no.sikt.graphitron.queries.fetch;

import no.sikt.graphitron.common.GeneratorTest;
import no.sikt.graphitron.common.configuration.SchemaComponent;
import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.reducedgenerators.InterfaceOnlyFetchDBClassGenerator;
import no.sikt.graphql.directives.GenerationDirective;
import no.sikt.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.common.configuration.SchemaComponent.NODE;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

@DisplayName("Query node - Interface handling for types implementing node interface")
public class NodeInterfaceTest extends GeneratorTest {
    @Override
    protected String getSubpath() {
        return "queries/fetch/interfaces/node";
    }

    @Override
    protected Set<SchemaComponent> getComponents() {
        return makeComponents(NODE);
    }

    @Override
    protected List<ClassGenerator> makeGenerators(ProcessedSchema schema) {
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
    @DisplayName("Type implements node and non-node interface")
    void twoInterfaces() {
        assertGeneratedContentContains(
                "twoInterfaces",
                "customerForNode",
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
}
