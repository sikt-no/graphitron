package no.sikt.graphitron.queries.fetch;

import no.sikt.graphitron.common.GeneratorTest;
import no.sikt.graphitron.common.configuration.SchemaComponent;
import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.reducedgenerators.MapOnlyFetchDBClassGenerator;
import no.sikt.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.common.configuration.SchemaComponent.CUSTOMER_TABLE;

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
    protected List<ClassGenerator> makeGenerators(ProcessedSchema schema) {
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
        assertGeneratedContentContains("nonIDKey", ",List<Integer> integer,", ".select(_film.LENGTH.cast(String.class),");
    }

    @Test
    @DisplayName("Multiple keys")
    void manyKeys() {
        assertGeneratedContentContains(
                "manyKeys",
                ",List<String> firstName,List<String> lastName,",
                ".select(DSL.concat(DSL.inlined(_customer.FIRST_NAME), DSL.inline(\",\"), DSL.inlined(_customer.LAST_NAME)),"
        );
    }

    @Test
    @DisplayName("One key and one non-key field")
    void otherNonKeyField() {
        assertGeneratedContentContains(
                "otherNonKeyField",
                ",List<String> firstName,List<String> lastName,",
                ".select(_customer.FIRST_NAME,"
        );
    }

    @Test
    @DisplayName("Key inside listed input type")
    void inputKey() {
        assertGeneratedContentContains("inputKey", ",List<Input> in,", ".select(_customer.getId(),");
    }

    @Test
    @DisplayName("Key listed inside input type")
    void listedKeyInInput() {
        assertGeneratedContentContains("listedKeyInInput", ", Input in,", ".select(_customer.getId(),");
    }
}
