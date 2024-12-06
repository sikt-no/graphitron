package no.sikt.graphitron.queries.fetch;

import no.sikt.graphitron.common.GeneratorTest;
import no.sikt.graphitron.common.configuration.SchemaComponent;
import no.sikt.graphitron.definitions.interfaces.GenerationTarget;
import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.reducedgenerators.InterfaceOnlyFetchDBClassGenerator;
import no.sikt.graphql.directives.GenerationDirective;
import no.sikt.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.common.configuration.SchemaComponent.CUSTOMER_TABLE;
import static no.sikt.graphitron.common.configuration.SchemaComponent.NODE;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

@DisplayName("Query interfaces - Interface handling for types implementing interfaces")
public class InterfaceTest extends GeneratorTest {
    @Override
    protected String getSubpath() {
        return "queries/fetch/interfaces/standard";
    }

    @Override
    protected List<ClassGenerator<? extends GenerationTarget>> makeGenerators(ProcessedSchema schema) {
        return List.of(new InterfaceOnlyFetchDBClassGenerator(schema));
    }

    @Test
    @DisplayName("Default case")
    void defaultCase() {
        assertGeneratedContentMatches("default");
    }

    @Test
    @Disabled("Disablet inntil A51-371 er på plass")
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
    @Disabled("Disablet inntil A51-371 er på plass")
    @DisplayName("Interface with type reference with splitQuery should not have CustomerTable in subquery")
    void interfaceWithTypeSplitQuery() {
        assertGeneratedContentContains(
                "interfaceWithTypeSplitQuery",
                Set.of(CUSTOMER_TABLE),
                "row(_payment.getId()).mapping"
        );
    }
}
