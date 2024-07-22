package no.fellesstudentsystem.graphitron_newtestorder.javarecordmappers;

import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationTarget;
import no.fellesstudentsystem.graphitron.generators.abstractions.ClassGenerator;
import no.fellesstudentsystem.graphitron.generators.resolvers.mapping.JavaRecordMapperClassGenerator;
import no.fellesstudentsystem.graphitron_newtestorder.GeneratorTest;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static no.fellesstudentsystem.graphitron_newtestorder.ReferenceTestSet.*;

@DisplayName("Java Mappers - Mapper content for mapping Java records to graph types")
public class JavaMapperGeneratorToGraphTest extends GeneratorTest {
    public static final String SRC_TEST_RESOURCES_PATH = "javamappers/tograph";

    public JavaMapperGeneratorToGraphTest() {
        super(
                SRC_TEST_RESOURCES_PATH,
                DUMMY_SERVICE.get(),
                DUMMY_ENUM.get(),
                JAVA_RECORD_CUSTOMER.get(),
                MAPPER_RECORD_ADDRESS.get(),
                MAPPER_RECORD_FILM.get()
        );
    }

    @Override
    protected List<ClassGenerator<? extends GenerationTarget>> makeGenerators(ProcessedSchema schema) {
        return List.of(new JavaRecordMapperClassGenerator(schema, false));
    }

    @Test
    @DisplayName("Default case with simple record mapper")
    void defaultCase() {
        assertGeneratedContentMatches("default");
    }

    @Test
    @DisplayName("Java record containing non-record type")
    void containingNonRecordWrapper() {
        assertGeneratedContentMatches("containingNonRecordWrapper");
    }

    @Test
    @DisplayName("Java record containing non-record type and using field overrides")
    void containingNonRecordWrapperWithFieldOverride() {
        assertGeneratedContentMatches("containingNonRecordWrapperWithFieldOverride");
    }

    @Test
    @DisplayName("Skips fields with splitQuery set")
    void skipsSplitQuery() {
        assertGeneratedContentMatches("skipsSplitQuery");
    }

    @Test
    @DisplayName("Skips fields that are not mapped to a record field")
    void unconfiguredField() {
        assertGeneratedContentMatches("unconfiguredField");
    }

    @Test
    @DisplayName("Maps ID fields that are not the primary key")
    void idOtherThanPK() {
        assertGeneratedContentMatches("idOtherThanPK");
    }

    @Test
    @DisplayName("Records with enum fields")
    void withEnum() {
        assertGeneratedContentMatches("withEnum");
    }

    @Test
    @DisplayName("Records with list fields")
    void listField() {
        assertGeneratedContentMatches("listField");
    }
}
