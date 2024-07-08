package no.fellesstudentsystem.graphitron_newtestorder.resolvers;

import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationTarget;
import no.fellesstudentsystem.graphitron.generators.abstractions.ClassGenerator;
import no.fellesstudentsystem.graphitron.generators.resolvers.fetch.FetchResolverClassGenerator;
import no.fellesstudentsystem.graphitron_newtestorder.GeneratorTest;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

@Disabled
@DisplayName("Fetch condition resolvers - Resolvers that contain conditions")
public class FetchConditionResolverTest extends GeneratorTest {
    public static final String SRC_TEST_RESOURCES_PATH = "resolvers/fetch";

    public FetchConditionResolverTest() {
        super(SRC_TEST_RESOURCES_PATH, List.of());
    }

    @Override
    protected List<ClassGenerator<? extends GenerationTarget>> makeGenerators(ProcessedSchema schema) {
        return List.of(new FetchResolverClassGenerator(schema));
    }

    @Test
    @DisplayName("Conditions with input Java records")
    void withInputJavaRecord() {
        assertGeneratedContentMatches("withInputJavaRecord");
    }

    @Test
    @DisplayName("Conditions with input jOOQ records")
    void withInputJOOQRecord() {
        assertGeneratedContentMatches("withInputJOOQRecord");
    }
}
