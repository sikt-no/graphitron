package no.sikt.graphitron.exceptionhandling;

import no.sikt.graphitron.common.GeneratorTest;
import no.sikt.graphitron.common.configuration.SchemaComponent;
import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.configuration.RecordValidation;
import no.sikt.graphitron.configuration.externalreferences.ExternalReference;
import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.generators.exception.ExceptionToErrorMappingProviderGenerator;
import no.sikt.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.common.configuration.ReferencedEntry.DUMMY_SERVICE;
import static no.sikt.graphitron.common.configuration.SchemaComponent.ERROR;

@DisplayName("Exception handling - Provider generation is split into methods when the constructor would exceed the JVM method size limit")
public class ProviderChunkingTest extends GeneratorTest {
    @Override
    protected String getSubpath() {
        return "exceptions/provider";
    }

    @Override
    protected Set<ExternalReference> getExternalReferences() {
        return makeReferences(DUMMY_SERVICE);
    }

    @Override
    protected Set<SchemaComponent> getComponents() {
        return makeComponents(ERROR);
    }

    @Override
    protected List<ClassGenerator> makeGenerators(ProcessedSchema schema) {
        // A method size limit this small forces each declaration into its own method.
        return List.of(new ExceptionToErrorMappingProviderGenerator(schema, 1));
    }

    @BeforeEach
    public void setup() {
        super.setup();
        GeneratorConfig.setRecordValidation(new RecordValidation(true, null));
    }

    @Test
    @DisplayName("Mappings are hoisted to fields and initialization is split into multiple methods")
    void chunked() {
        assertGeneratedContentContains(
                "multiple",
                "private GenericExceptionContentToErrorMapping m1;",
                "private GenericExceptionContentToErrorMapping m2;",
                "dataAccessMappingsForOperation = new HashMap<>();" +
                        "genericMappingsForOperation = new HashMap<>();" +
                        "initMappings1();initMappings2();initMappings3();initMappings4();",
                "private void initMappings1() {" +
                        "m1 = new GenericExceptionContentToErrorMapping(" +
                        "new GenericExceptionMatcher(\"java.lang.IllegalArgumentException\", null),(path, msg) -> new SomeError0(path, msg));}",
                "private void initMappings3() {" +
                        "var mutation0GenericList = List.of(m1);" +
                        "genericMappingsForOperation.put(\"mutation0\", mutation0GenericList);}"
        );
    }

    @Test
    @DisplayName("A shared list and all operations using it stay in the same method")
    void chunkedSharedList() {
        assertGeneratedContentContains(
                "sharedLists",
                "initMappings1();initMappings2();",
                "private void initMappings2() {" +
                        "var sharedGenericList1 = List.of(m1);" +
                        "genericMappingsForOperation.put(\"mutation0\", sharedGenericList1);" +
                        "genericMappingsForOperation.put(\"mutation1\", sharedGenericList1);}"
        );
    }
}
