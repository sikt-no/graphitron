package no.fellesstudentsystem.graphitron_newtestorder.resolvers.standard.fetch;

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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    @DisplayName("Sorting on a field that is inaccessible")
    void inaccessibleField() {
        assertThatThrownBy(() -> generateFiles("inaccessibleField", Set.of(PAGE_INFO, ORDER)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("OrderByField 'LANGUAGE' refers to index 'IDX_FK_LANGUAGE_ID' on field 'language_id' but this field is not accessible from the schema type 'Film'");
    }
}
