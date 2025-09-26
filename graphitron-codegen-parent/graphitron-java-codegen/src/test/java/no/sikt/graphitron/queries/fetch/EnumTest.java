package no.sikt.graphitron.queries.fetch;

import no.sikt.graphitron.common.GeneratorTest;
import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.reducedgenerators.MapOnlyFetchDBClassGenerator;
import no.sikt.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.common.configuration.SchemaComponent.DUMMY_ENUM;
import static no.sikt.graphitron.common.configuration.SchemaComponent.DUMMY_ENUM_CONVERTED;

@DisplayName("Query enums - Positioning of enums in queries")
public class EnumTest extends GeneratorTest {
    @Override
    protected String getSubpath() {
        return "queries/fetch/enums";
    }

    @Override
    protected List<ClassGenerator> makeGenerators(ProcessedSchema schema) {
        return List.of(new MapOnlyFetchDBClassGenerator(schema));
    }

    @Test
    @DisplayName("Input JOOQ enum")
    void inputJOOQ() {
        assertGeneratedContentContains(
                "input/jOOQ", Set.of(DUMMY_ENUM_CONVERTED),
                ", DummyEnumConverted e,",
                "e != null ? _film.RATING.convert(DummyEnumConverted.class,",
                ".eq(e) : DSL.noCondition()"
        );
    }

    @Test
    @DisplayName("Input string enum")
    void inputString() {
        assertGeneratedContentContains(
                "input/string", Set.of(DUMMY_ENUM),
                ", DummyEnum e,",
                "e != null ? _film.RATING.convert(DummyEnum.class,",
                ".eq(e) : DSL.noCondition()"
        );
    }

    @Test
    @DisplayName("Input jOOQ record containing an enum")
    void jOOQRecord() {
        assertGeneratedContentContains(
                "input/jOOQRecord", Set.of(DUMMY_ENUM_CONVERTED),
                "inRecord.getRating() != null ? _film.RATING.eq(inRecord.getRating()) : DSL.noCondition()"
        );
    }

    @Test
    @DisplayName("Input jOOQ record containing a string enum")
    void jOOQRecordString() {
        assertGeneratedContentContains(
                "input/jOOQRecordString", Set.of(DUMMY_ENUM),
                "inRecord.getRating() != null ? _film.RATING.eq(inRecord.getRating()) : DSL.noCondition()"
        );
    }

    @Test
    @DisplayName("Listed input jOOQ record containing an enum")
    void listedJOOQRecord() {
        assertGeneratedContentContains(
                "input/listedJOOQRecord", Set.of(DUMMY_ENUM_CONVERTED),
                "inRecordList.get(internal_it_).getRating()) : _film.RATING)"
        );
    }

    @Test
    @DisplayName("Listed input jOOQ record containing an enum with additional schema layers")
    void listedJOOQRecordNested() {
        assertGeneratedContentContains(
                "input/listedJOOQRecordNested", Set.of(DUMMY_ENUM_CONVERTED),
                "DSL.val(inRecordList.get(internal_it_).getRating()) : _film.RATING)"
        );
    }

    @Test
    @DisplayName("Output JOOQ enum")
    void outputJOOQ() {
        assertGeneratedContentContains(
                "output/jOOQ", Set.of(DUMMY_ENUM_CONVERTED),
                "_film.RATING.convert(DummyEnumConverted.class,"
        );
    }

    @Test
    @DisplayName("Output string enum")
    void outputString() {
        assertGeneratedContentContains(
                "output/string", Set.of(DUMMY_ENUM),
                "_film.RATING.convert(DummyEnum.class,"
        );
    }

    @Test
    @DisplayName("Output JOOQ enum in subquery")
    void outputJOOQSubquery() {
        assertGeneratedContentContains(
                "output/jOOQSubquery", Set.of(DUMMY_ENUM_CONVERTED),
                "inventory_4239518507_film.RATING.convert("
        );
    }
}
