package no.fellesstudentsystem.graphitron_newtestorder.javarecordmappers;

import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationTarget;
import no.fellesstudentsystem.graphitron.generators.abstractions.ClassGenerator;
import no.fellesstudentsystem.graphitron.generators.resolvers.mapping.JavaRecordMapperClassGenerator;
import no.fellesstudentsystem.graphitron_newtestorder.GeneratorTest;
import no.fellesstudentsystem.graphitron_newtestorder.dummygenerators.DummyTransformerClassGenerator;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static no.fellesstudentsystem.graphitron_newtestorder.ReferenceTestSet.*;

// This is split here so the dummy transformer is not included in other tests.
@DisplayName("Java Mappers - Mapper containing additional records")
public class JavaMapperGeneratorNestingTest extends GeneratorTest {
    public static final String SRC_TEST_RESOURCES_PATH = "javamappers";
    public JavaMapperGeneratorNestingTest() {
        super(
                SRC_TEST_RESOURCES_PATH,
                DUMMY_SERVICE.get(),
                MAPPER_RECORD_ADDRESS.get(),
                MAPPER_RECORD_CITY.get(),
                JAVA_RECORD_CUSTOMER.get()
        );
    }

    @Override
    protected List<ClassGenerator<? extends GenerationTarget>> makeGenerators(ProcessedSchema schema) {
        return List.of(new JavaRecordMapperClassGenerator(schema, false), new JavaRecordMapperClassGenerator(schema, true), new DummyTransformerClassGenerator(schema));
    }

    @Test
    @DisplayName("Responses containing other records")
    void responsesContainingRecords() {
        assertGeneratedContentMatches("tograph/containingRecords");
    }

    @Test
    @DisplayName("Responses containing jOOQ records fetched by ID")
    void responsesContainingRecordFetchedByID() {
        assertGeneratedContentMatches("tograph/containingRecordFetchedByID");
    }

    @Test
    @DisplayName("Responses containing other records with a non-record type in between")
    void responsesContainingNonRecordWrapperWithRecord() {
        assertGeneratedContentMatches("tograph/containingNonRecordWrapperWithRecord");
    }

    @Test
    @DisplayName("Responses skip fields that are not mapped to a record")
    void responsesWithUnconfiguredRecord() {
        assertGeneratedContentMatches("tograph/unconfiguredRecord");
    }

    @Test
    @DisplayName("Inputs containing other records")
    void inputsContainingRecords() {
        assertGeneratedContentMatches("torecord/containingRecords");
    }

    @Test
    @DisplayName("Inputs containing other records with a non-record type in between")
    void inputsContainingNonRecordWrapperWithRecord() {
        assertGeneratedContentMatches("torecord/containingNonRecordWrapperWithRecord");
    }

    @Test
    @DisplayName("Inputs skip fields that are not mapped to a record")
    void inputsWithUnconfiguredRecord() {
        assertGeneratedContentMatches("torecord/unconfiguredRecord");
    }
}
