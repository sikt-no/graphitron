package no.fellesstudentsystem.graphitron;

import no.fellesstudentsystem.graphitron.configuration.GeneratorConfig;
import no.fellesstudentsystem.graphitron.configuration.externalreferences.TransformScope;
import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationTarget;
import no.fellesstudentsystem.graphitron.generators.abstractions.ClassGenerator;
import no.fellesstudentsystem.graphitron.generators.db.UpdateDBClassGenerator;
import no.fellesstudentsystem.graphitron.generators.resolvers.UpdateResolverClassGenerator;
import no.fellesstudentsystem.graphitron.mojo.GlobalTransform;
import no.fellesstudentsystem.graphitron.transforms.SomeTransform;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Disabled
public class GraphQLGeneratorTransformTest extends TestCommon {
    public static final String SRC_TEST_RESOURCES_PATH = "transform";
    private final Map<String, Method> transforms = Map.of(
            "TEST_TRANSFORM", SomeTransform.class.getMethod("someTransform", DSLContext.class, List.class)
    );

    private final List<GlobalTransform> globalTransforms = List.of(
            new GlobalTransform("TEST_TRANSFORM", TransformScope.ALL_MUTATIONS)
    );

    public GraphQLGeneratorTransformTest() throws NoSuchMethodException {
        super(SRC_TEST_RESOURCES_PATH);
    }

    @Override
    protected Map<String, List<String>> generateFiles(String schemaParentFolder) throws IOException {
        var processedSchema = getProcessedSchema(schemaParentFolder, false);
        List<ClassGenerator<? extends GenerationTarget>> generators = List.of(
                new UpdateResolverClassGenerator(processedSchema),
                new UpdateDBClassGenerator(processedSchema)
        );

        return generateFiles(generators);
    }

    @Override
    protected void setProperties() {
        GeneratorConfig.setProperties(
                DEFAULT_SYSTEM_PACKAGE,
                Set.of(),
                tempOutputDirectory.toString(),
                DEFAULT_OUTPUT_PACKAGE,
                DEFAULT_JOOQ_PACKAGE,
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                transforms,
                globalTransforms
        );
    }

    @Test
    void generate_mutation_shouldGenerateResolversWithTransform() throws IOException {
        assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder("resolverWithTransforms");
    }
}
