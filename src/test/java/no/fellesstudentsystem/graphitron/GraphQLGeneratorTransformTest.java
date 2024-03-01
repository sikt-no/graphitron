package no.fellesstudentsystem.graphitron;

import no.fellesstudentsystem.graphitron.configuration.GeneratorConfig;
import no.fellesstudentsystem.graphitron.configuration.externalreferences.ExternalClassReference;
import no.fellesstudentsystem.graphitron.configuration.externalreferences.GlobalTransform;
import no.fellesstudentsystem.graphitron.configuration.externalreferences.TransformScope;
import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationTarget;
import no.fellesstudentsystem.graphitron.generators.abstractions.ClassGenerator;
import no.fellesstudentsystem.graphitron.generators.db.update.UpdateDBClassGenerator;
import no.fellesstudentsystem.graphitron.generators.resolvers.mapping.RecordMapperClassGenerator;
import no.fellesstudentsystem.graphitron.generators.resolvers.mapping.TransformerClassGenerator;
import no.fellesstudentsystem.graphitron.generators.resolvers.update.UpdateResolverClassGenerator;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class GraphQLGeneratorTransformTest extends TestCommon {
    public static final String SRC_TEST_RESOURCES_PATH = "transform";
    private final List<ExternalClassReference> references =
            List.of(new ExternalClassReference("TEST_TRANSFORM", "no.fellesstudentsystem.graphitron.transforms.SomeTransform"));

    private final List<GlobalTransform> globalTransforms = List.of(
            new GlobalTransform("TEST_TRANSFORM", "someTransform", TransformScope.ALL_MUTATIONS)
    );

    public GraphQLGeneratorTransformTest() {
        super(SRC_TEST_RESOURCES_PATH);
    }

    @Override
    protected Map<String, List<String>> generateFiles(String schemaParentFolder) throws IOException {
        var processedSchema = getProcessedSchema(schemaParentFolder);
        List<ClassGenerator<? extends GenerationTarget>> generators = List.of(
                new UpdateResolverClassGenerator(processedSchema),
                new UpdateDBClassGenerator(processedSchema),
                new TransformerClassGenerator(processedSchema),
                new RecordMapperClassGenerator(processedSchema, true)
        );

        return generateFiles(generators);
    }

    @Override
    protected void setProperties() {
        GeneratorConfig.setProperties(
                Set.of(),
                tempOutputDirectory.toString(),
                DEFAULT_OUTPUT_PACKAGE,
                DEFAULT_JOOQ_PACKAGE,
                references,
                globalTransforms,
                List.of()
        );
    }

    @Test
    void generate_mutation_shouldGenerateResolversWithTransform() throws IOException {
        assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder("resolverWithTransforms");
    }
}
