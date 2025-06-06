package no.sikt.graphitron.javarecordmappers;

import no.sikt.graphitron.common.GeneratorTest;
import no.sikt.graphitron.configuration.externalreferences.ExternalReference;
import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.generators.mapping.JavaRecordMapperClassGenerator;
import no.sikt.graphitron.reducedgenerators.dummygenerators.DummyTransformerClassGenerator;
import no.sikt.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.common.configuration.ReferencedEntry.*;
import static no.sikt.graphitron.common.configuration.SchemaComponent.*;

// This is split here so the dummy transformer is not included in other tests.
@DisplayName("Java Mappers - Mapper containing additional records")
public class JavaMapperGeneratorNestingTest extends GeneratorTest {
    @Override
    protected String getSubpath() {
        return "javamappers";
    }

    @Override
    protected Set<ExternalReference> getExternalReferences() {
        return makeReferences(DUMMY_SERVICE, NESTED_RECORD, JAVA_RECORD_CUSTOMER);
    }

    @Override
    protected List<ClassGenerator> makeGenerators(ProcessedSchema schema) {
        return List.of(new JavaRecordMapperClassGenerator(schema, false), new JavaRecordMapperClassGenerator(schema, true), new DummyTransformerClassGenerator(schema));
    }

    @Test
    @DisplayName("Responses containing a jOOQ record")
    void responsesContainingJOOQRecord() {
        assertGeneratedContentMatches("tograph/containingJOOQRecord", ADDRESS_SERVICE, CUSTOMER_TABLE);
    }

    @Test
    @DisplayName("Responses containing a Java record")
    void responsesContainingJavaRecord() {
        assertGeneratedContentMatches("tograph/containingJavaRecord", ADDRESS_SERVICE, DUMMY_TYPE_RECORD);
    }

    @Test
    @DisplayName("Inputs containing a Java record")
    void inputsContainingJavaRecord() {
        assertGeneratedContentMatches("torecord/containingJavaRecord", DUMMY_INPUT_RECORD);
    }

    @Test
    @DisplayName("Inputs containing a jOOQ record")
    void inputsContainingJOOQRecord() {
        assertGeneratedContentMatches("torecord/containingJOOQRecord", CUSTOMER_INPUT_TABLE);
    }
}
