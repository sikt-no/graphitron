package no.fellesstudentsystem.graphitron_newtestorder.services;

import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationTarget;
import no.fellesstudentsystem.graphitron.generators.abstractions.ClassGenerator;
import no.fellesstudentsystem.graphitron.generators.resolvers.fetch.FetchResolverClassGenerator;
import no.fellesstudentsystem.graphitron_newtestorder.GeneratorTest;
import no.fellesstudentsystem.graphitron_newtestorder.dummygenerators.DummyTransformerClassGenerator;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static no.fellesstudentsystem.graphitron_newtestorder.ReferenceTestSet.*;

@DisplayName("Fetch service resolvers - Resolvers that call custom services")
public class FetchServiceResolverTest extends GeneratorTest {
    public static final String SRC_TEST_RESOURCES_PATH = "resolvers/services/fetch";

    public FetchServiceResolverTest() {
        super(SRC_TEST_RESOURCES_PATH, JOOQ_RECORD_FETCH_SERVICE.get(), JAVA_RECORD_FETCH_SERVICE.get(), DUMMY_RECORD.get());
    }

    @Override
    protected List<ClassGenerator<? extends GenerationTarget>> makeGenerators(ProcessedSchema schema) {
        return List.of(new FetchResolverClassGenerator(schema), new DummyTransformerClassGenerator(schema));
    }

    @Test
    @DisplayName("Service including input Java records")
    void withInputJavaRecord() {
        assertGeneratedContentMatches("withInputJavaRecord");
    }

    @Test
    @DisplayName("Service including input jOOQ records")
    void withInputJOOQRecord() {
        assertGeneratedContentMatches("withInputJOOQRecord");
    }
}
