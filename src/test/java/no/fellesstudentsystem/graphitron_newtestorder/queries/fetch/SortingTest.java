package no.fellesstudentsystem.graphitron_newtestorder.queries.fetch;

import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationTarget;
import no.fellesstudentsystem.graphitron.generators.abstractions.ClassGenerator;
import no.fellesstudentsystem.graphitron_newtestorder.GeneratorTest;
import no.fellesstudentsystem.graphitron_newtestorder.SchemaComponent;
import no.fellesstudentsystem.graphitron_newtestorder.reducedgenerators.MapOnlyFetchDBClassGenerator;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.fellesstudentsystem.graphitron_newtestorder.SchemaComponent.*;
import static no.fellesstudentsystem.graphql.directives.GenerationDirective.INDEX;
import static no.fellesstudentsystem.graphql.directives.GenerationDirectiveParam.NAME;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Sorting - Queries with custom ordering")
public class SortingTest extends GeneratorTest {
    @Override
    protected String getSubpath() {
        return "queries/fetch/sorting";
    }

    @Override
    protected Set<SchemaComponent> getComponents() {
        return makeComponents(ORDER);
    }

    @Override
    protected List<ClassGenerator<? extends GenerationTarget>> makeGenerators(ProcessedSchema schema) {
        return List.of(new MapOnlyFetchDBClassGenerator(schema));
    }

    @Test
    @DisplayName("One sorting parameter")
    void defaultCase() {
        assertGeneratedContentMatches("default");
    }

    @Test
    @DisplayName("Including pagination")
    void paginated() {
        assertGeneratedContentMatches("paginated", CUSTOMER_CONNECTION_ORDER, PAGE_INFO);
    }

    @Test
    @DisplayName("No list or pagination") // Does not do any ordering.
    void withoutList() {
        assertGeneratedContentMatches("withoutList");
    }

    @Test
    @DisplayName("Multiple fields")
    void twoFields() {
        assertGeneratedContentContains(
                "twoFields",
                ".entry(\"STORE\", \"IDX_FK_STORE_ID\"),Map.entry(\"NAME\", \"IDX_LAST_NAME\""
        );
    }

    @Test
    @DisplayName("Sorting parameter on a two field index")
    void twoFieldIndex() {
        assertGeneratedContentContains(
                "twoFieldIndex",
                ".ofEntries(Map.entry(\"STORE_ID_FILM_ID\", \"IDX_STORE_ID_FILM_ID\"))"
        );
    }

    @Test
    @DisplayName("Sorting on a parameter that has an invalid index")
    void wrongIndex() {
        assertThatThrownBy(() -> generateFiles("wrongIndex", Set.of(CUSTOMER_TABLE)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Table 'CUSTOMER' has no index 'WRONG_INDEX' necessary for sorting by 'EMAIL'");
    }

    @Test
    @DisplayName("Sorting parameter without index set")
    void missingDirective() {
        assertThatThrownBy(() -> generateFiles("missingDirective", Set.of(CUSTOMER_TABLE)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Expected enum field 'NAME' of 'OrderByField' to have an '@%s(%s: ...)' directive, but no such directive was set", INDEX.getName(), NAME.getName());
    }
}
