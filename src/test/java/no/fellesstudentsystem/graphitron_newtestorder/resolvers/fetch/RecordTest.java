package no.fellesstudentsystem.graphitron_newtestorder.resolvers.fetch;

import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationTarget;
import no.fellesstudentsystem.graphitron.generators.abstractions.ClassGenerator;
import no.fellesstudentsystem.graphitron.generators.resolvers.fetch.FetchResolverClassGenerator;
import no.fellesstudentsystem.graphitron_newtestorder.GeneratorTest;
import no.fellesstudentsystem.graphitron_newtestorder.dummygenerators.DummyTransformerClassGenerator;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static no.fellesstudentsystem.graphitron_newtestorder.ReferenceTestSet.DUMMY_RECORD;

@DisplayName("Fetch resolvers - Resolvers with input records")
public class RecordTest extends GeneratorTest {
    public static final String SRC_TEST_RESOURCES_PATH = "resolvers/standard/fetch/operation";

    public RecordTest() {
        super(SRC_TEST_RESOURCES_PATH, DUMMY_RECORD.get());
    }

    @Override
    protected List<ClassGenerator<? extends GenerationTarget>> makeGenerators(ProcessedSchema schema) {
        return List.of(new FetchResolverClassGenerator(schema), new DummyTransformerClassGenerator(schema));
    }

    @Test
    @DisplayName("Query resolver with input Java records")
    void withInputJavaRecord() {
        assertGeneratedContentMatches("withInputJavaRecord");
    }

    @Test
    @DisplayName("Query resolver with input jOOQ records")
    void withInputJOOQRecord() {
        assertGeneratedContentMatches("withInputJOOQRecord");
    }
}
