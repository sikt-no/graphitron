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

@DisplayName("Lookup Queries - Queries using lookup keys")
public class LookupTest extends GeneratorTest {
    @Override
    protected String getSubpath() {
        return "queries/fetch/lookup";
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
    @DisplayName("One key")
    void defaultCase() {
        assertGeneratedContentMatches("default");
    }

    @Test
    @DisplayName("Key other than an ID")
    void nonIDKey() {
        assertGeneratedContentContains("nonIDKey", ",List<Integer> integer,", ".select(FILM.LENGTH,");
    }

    @Test
    @DisplayName("Multiple keys")
    void manyKeys() {
        assertGeneratedContentContains(
                "manyKeys",
                ",List<String> firstName,List<String> lastName,",
                ".select(DSL.concat(DSL.inlined(CUSTOMER.FIRST_NAME), DSL.inline(\",\"), DSL.inlined(CUSTOMER.LAST_NAME)),"
        );
    }

    @Test
    @DisplayName("One key and one non-key field")
    void otherNonKeyField() {
        assertGeneratedContentContains(
                "otherNonKeyField",
                ",List<String> firstName,List<String> lastName,",
                ".select(CUSTOMER.FIRST_NAME,"
        );
    }

    @Test
    @DisplayName("Key inside listed input type")
    void inputKey() {
        assertGeneratedContentContains("inputKey", ",List<Input> in,", ".select(CUSTOMER.getId(),");
    }

    @Test
    @DisplayName("Key listed inside input type")
    void listedKeyInInput() {
        assertGeneratedContentContains("listedKeyInInput", ", Input in,", ".select(CUSTOMER.getId(),");
    }
}
