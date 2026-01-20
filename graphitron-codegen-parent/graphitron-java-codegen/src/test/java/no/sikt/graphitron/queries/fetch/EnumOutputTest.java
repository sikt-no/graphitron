package no.sikt.graphitron.queries.fetch;

import no.sikt.graphitron.common.GeneratorTest;
import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.reducedgenerators.QueryOnlyHelperDBClassGenerator;
import no.sikt.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.common.configuration.SchemaComponent.DUMMY_ENUM;
import static no.sikt.graphitron.common.configuration.SchemaComponent.DUMMY_ENUM_CONVERTED;

@DisplayName("Query enums - Positioning of enums in query outputs")
public class EnumOutputTest extends GeneratorTest {
    @Override
    protected String getSubpath() {
        return "queries/fetch/enums";
    }

    @Override
    protected List<ClassGenerator> makeGenerators(ProcessedSchema schema) {
        return List.of(new QueryOnlyHelperDBClassGenerator(schema));
    }

    @Test
    @DisplayName("Output JOOQ enum")
    void outputJOOQ() {
        assertGeneratedContentContains(
                "output/jOOQ", Set.of(DUMMY_ENUM_CONVERTED),
                "_a_film.RATING.convert(DummyEnumConverted.class,"
        );
    }

    @Test
    @DisplayName("Output string enum")
    void outputString() {
        assertGeneratedContentContains(
                "output/string", Set.of(DUMMY_ENUM),
                "_a_film.RATING.convert(DummyEnum.class,"
        );
    }

    @Test
    @DisplayName("Output JOOQ enum in subquery")
    void outputJOOQSubquery() {
        assertGeneratedContentContains(
                "output/jOOQSubquery", Set.of(DUMMY_ENUM_CONVERTED),
                "inventory_2972535350_film.RATING.convert("
        );
    }
}
