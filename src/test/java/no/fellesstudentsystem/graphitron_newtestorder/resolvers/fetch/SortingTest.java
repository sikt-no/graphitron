package no.fellesstudentsystem.graphitron_newtestorder.resolvers.fetch;

import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationTarget;
import no.fellesstudentsystem.graphitron.generators.abstractions.ClassGenerator;
import no.fellesstudentsystem.graphitron.generators.resolvers.fetch.FetchResolverClassGenerator;
import no.fellesstudentsystem.graphitron_newtestorder.GeneratorTest;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.fellesstudentsystem.graphitron_newtestorder.SchemaComponent.*;

@DisplayName("Sorting - Resolvers with custom ordering")
public class SortingTest extends GeneratorTest {
    @Override
    protected String getSubpath() {
        return "resolvers/fetch/sorting";
    }

    @Override
    protected List<ClassGenerator<? extends GenerationTarget>> makeGenerators(ProcessedSchema schema) {
        return List.of(new FetchResolverClassGenerator(schema));
    }

    @Test
    @DisplayName("Basic resolver with a sorting parameter")
    void defaultCase() {
        assertGeneratedContentMatches("default", CUSTOMER_CONNECTION_ORDER);
    }

    @Test
    @DisplayName("Basic resolver with a sorting parameter on a split query")
    void splitQuery() {
        assertGeneratedContentMatches("splitQuery", SPLIT_QUERY_WRAPPER, CUSTOMER_CONNECTION_ORDER);
    }

    @Test
    @DisplayName("Resolver with two sorting parameters")
    void twoFields() {
        assertGeneratedContentContains(
                "twoFields", Set.of(CUSTOMER_CONNECTION_ORDER),
                "of(\"NAME\", type -> type.getName(), \"STORE\", type -> type.getStoreId())"
        );
    }

    @Test
    @DisplayName("Resolver with a nested sorting parameter")
    void nestedField() {
        assertGeneratedContentContains(
                "nestedField", Set.of(CUSTOMER_CONNECTION_ORDER),
                "\"NAME\", type -> type.getNested().getName()"
        );
    }

    @Test
    @DisplayName("Resolver with a double nested sorting parameter")
    void doubleNestedField() {
        assertGeneratedContentContains(
                "doubleNestedField", Set.of(CUSTOMER_CONNECTION_ORDER),
                "\"NAME\", type -> type.getNested().getNested().getName()"
        );
    }

    @Test
    @DisplayName("Resolver with a sorting parameter on a two field index")
    void twoFieldIndex() {
        assertGeneratedContentContains(
                "twoFieldIndex", Set.of(PAGE_INFO, ORDER),
                "\"STORE_ID_FILM_ID\", type -> type.getStoreId() + \",\" + type.getFilmId()"
        );
    }
}
