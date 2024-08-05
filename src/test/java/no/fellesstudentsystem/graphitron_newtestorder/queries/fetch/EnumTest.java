package no.fellesstudentsystem.graphitron_newtestorder.queries.fetch;

import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationTarget;
import no.fellesstudentsystem.graphitron.generators.abstractions.ClassGenerator;
import no.fellesstudentsystem.graphitron.generators.db.fetch.FetchDBClassGenerator;
import no.fellesstudentsystem.graphitron_newtestorder.GeneratorTest;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.fellesstudentsystem.graphitron_newtestorder.SchemaComponent.DUMMY_ENUM;
import static no.fellesstudentsystem.graphitron_newtestorder.SchemaComponent.DUMMY_ENUM_CONVERTED;

@DisplayName("Query input enums - Positioning of enums in queries")
public class EnumTest extends GeneratorTest {
    @Override
    protected String getSubpath() {
        return "queries/fetch/enums/input";
    }

    @Override
    protected List<ClassGenerator<? extends GenerationTarget>> makeGenerators(ProcessedSchema schema) {
        return List.of(new FetchDBClassGenerator(schema));
    }

    @Test
    @DisplayName("JOOQ enum")
    void jOOQQuery() {
        assertGeneratedContentContains(
                "jOOQQuery", Set.of(DUMMY_ENUM_CONVERTED),
                ", DummyEnumConverted e,",
                "e != null ? FILM.RATING.convert(DummyEnumConverted.class,",
                ".eq(e) : DSL.noCondition()"
        );
    }

    @Test
    @DisplayName("String enum")
    void stringQuery() {
        assertGeneratedContentContains(
                "stringQuery", Set.of(DUMMY_ENUM),
                ", DummyEnum e,",
                "e != null ? FILM.RATING.convert(DummyEnum.class,",
                ".eq(e) : DSL.noCondition()"
        );
    }
}
